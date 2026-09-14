CREATE OR REPLACE FUNCTION operations.reconcile_ingestion_coverage(p_consumer TEXT)
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE
    v_item RECORD;
    v_run RECORD;
    v_partition TEXT;
    v_start DATE;
    v_end DATE;
    v_gap DATE;
    v_through DATE;
    v_changed INTEGER := 0;
BEGIN
    -- Staging cannot race a consumer into completing an unfinished provider page.
    FOR v_item IN
        SELECT i.ingestion_run_item_id, i.ingestion_run_id
        FROM operations.ingestion_run_items i
        WHERE i.status = 'STAGED'
          AND EXISTS (SELECT 1 FROM operations.ingestion_item_events e
                      WHERE e.ingestion_run_item_id = i.ingestion_run_item_id)
          AND NOT EXISTS (
              SELECT 1 FROM operations.ingestion_item_events e
              JOIN operations.outbox_events o USING (event_id)
              LEFT JOIN operations.kafka_inbox inbox ON inbox.observation_key = o.observation_key
                  AND inbox.consumer_name = p_consumer AND inbox.processing_result = 'ACCEPTED'
              LEFT JOIN market_data.observations observation ON observation.observation_key = o.observation_key
              WHERE e.ingestion_run_item_id = i.ingestion_run_item_id
                AND (inbox.event_id IS NULL OR observation.observation_key IS NULL)
          )
          AND NOT EXISTS (SELECT 1 FROM operations.data_quality_issues q
              JOIN operations.ingestion_runs r ON r.ingestion_run_id = i.ingestion_run_id
              WHERE q.dataset_id = r.dataset_id AND q.status = 'OPEN' AND q.severity = 'BLOCKING'
                AND (q.ingestion_run_id IS NULL OR q.ingestion_run_id = i.ingestion_run_id)
                AND (q.instrument_id IS NULL OR q.instrument_id = i.instrument_id))
        ORDER BY i.ingestion_run_item_id FOR UPDATE OF i SKIP LOCKED
    LOOP
        UPDATE operations.ingestion_run_items SET status = 'COMPLETE', updated_at = clock_timestamp()
        WHERE ingestion_run_item_id = v_item.ingestion_run_item_id;
        v_changed := v_changed + 1;
    END LOOP;

    FOR v_run IN SELECT * FROM operations.ingestion_runs WHERE status <> 'COMPLETE'
        ORDER BY ingestion_run_id FOR UPDATE SKIP LOCKED
    LOOP
        UPDATE operations.ingestion_runs r SET
            staged_count = c.staged, accepted_count = c.accepted, failed_count = c.failed,
            status = CASE WHEN c.failed > 0 THEN 'FAILED'
                WHEN c.accepted = r.expected_count THEN 'COMPLETE'
                WHEN c.staged = r.expected_count THEN 'VALIDATING'
                WHEN c.running > 0 THEN 'RUNNING'
                WHEN c.retrying > 0 THEN 'WAITING_RETRY' ELSE 'PLANNED' END,
            completed_at = CASE WHEN c.accepted = r.expected_count THEN clock_timestamp() END,
            updated_at = clock_timestamp()
        FROM (SELECT COUNT(*) FILTER (WHERE status IN ('STAGED','COMPLETE')) staged,
                COUNT(*) FILTER (WHERE status = 'COMPLETE') accepted,
                COUNT(*) FILTER (WHERE status = 'FAILED') failed,
                COUNT(*) FILTER (WHERE status = 'RUNNING') running,
                COUNT(*) FILTER (WHERE status = 'WAITING_RETRY') retrying
              FROM operations.ingestion_run_items WHERE ingestion_run_id = v_run.ingestion_run_id) c
        WHERE r.ingestion_run_id = v_run.ingestion_run_id;
    END LOOP;

    -- A later blocking finding withdraws eligibility without deleting the audit record.
    UPDATE operations.data_coverage c SET valid = FALSE
    WHERE c.valid AND EXISTS (
        SELECT 1 FROM operations.data_quality_issues q
        WHERE q.dataset_id = c.dataset_id AND q.status = 'OPEN' AND q.severity = 'BLOCKING'
          AND (q.ingestion_run_id IS NULL OR q.ingestion_run_id = c.ingestion_run_id)
    );


    -- Coverage belongs to a dataset/job and exact snapshot pair, not to a process clock.
    FOR v_run IN
        SELECT r.*, r.job_definition_id::text || ':' || s.snapshots AS coverage_partition
        FROM operations.ingestion_runs r
        CROSS JOIN LATERAL (SELECT string_agg(universe_snapshot_id::text, ':' ORDER BY universe_snapshot_id) snapshots
            FROM operations.ingestion_run_snapshots WHERE ingestion_run_id = r.ingestion_run_id) s
        WHERE r.status = 'COMPLETE' AND r.window_start = r.window_end
          AND NOT EXISTS (SELECT 1 FROM operations.data_quality_issues q
              WHERE q.dataset_id = r.dataset_id AND q.status = 'OPEN' AND q.severity = 'BLOCKING'
                AND (q.ingestion_run_id IS NULL OR q.ingestion_run_id = r.ingestion_run_id))
    LOOP
        INSERT INTO operations.data_coverage (dataset_id, partition_key, boundary_date,
            ingestion_run_id, expected_count, accepted_count, validated_at)
        VALUES (v_run.dataset_id, v_run.coverage_partition, v_run.window_end,
            v_run.ingestion_run_id, v_run.expected_count, v_run.accepted_count, clock_timestamp())
        ON CONFLICT (dataset_id, partition_key, boundary_date) DO UPDATE
            SET ingestion_run_id = EXCLUDED.ingestion_run_id, expected_count = EXCLUDED.expected_count,
                accepted_count = EXCLUDED.accepted_count, validated_at = EXCLUDED.validated_at, valid = TRUE
            WHERE NOT operations.data_coverage.valid
              AND operations.data_coverage.ingestion_run_id <> EXCLUDED.ingestion_run_id;
    END LOOP;

    FOR v_run IN
        SELECT r.dataset_id, r.job_definition_id, s.snapshots, MIN(r.window_start) first_date, MAX(r.window_end) last_date
        FROM operations.ingestion_runs r
        CROSS JOIN LATERAL (SELECT string_agg(universe_snapshot_id::text, ':' ORDER BY universe_snapshot_id) snapshots
            FROM operations.ingestion_run_snapshots WHERE ingestion_run_id = r.ingestion_run_id) s
        GROUP BY r.dataset_id, r.job_definition_id, s.snapshots
    LOOP
        v_partition := v_run.job_definition_id::text || ':' || v_run.snapshots;
        v_start := v_run.first_date;
        v_end := v_run.last_date;
        -- A missing calendar row is a gap too: weekends/holidays are explicit rows.
        SELECT MIN(d::date) INTO v_gap FROM generate_series(v_start, v_end, INTERVAL '1 day') d
        LEFT JOIN reference.trading_sessions session ON session.exchange_mic = 'XNYS' AND session.session_date = d::date
        LEFT JOIN operations.data_coverage coverage ON coverage.dataset_id = v_run.dataset_id
            AND coverage.partition_key = v_partition AND coverage.boundary_date = d::date AND coverage.valid
        WHERE session.session_date IS NULL OR (NOT session.holiday AND coverage.boundary_date IS NULL);
        SELECT MAX(boundary_date) INTO v_through FROM operations.data_coverage
        WHERE dataset_id = v_run.dataset_id AND partition_key = v_partition AND valid
          AND boundary_date >= v_start AND (v_gap IS NULL OR boundary_date < v_gap);
        IF v_through IS NOT NULL THEN
            INSERT INTO operations.data_watermarks (dataset_id, partition_key, coverage_start, complete_through)
            VALUES (v_run.dataset_id, v_partition, v_start, v_through)
            ON CONFLICT (dataset_id, partition_key) DO UPDATE
                SET coverage_start = EXCLUDED.coverage_start, complete_through = EXCLUDED.complete_through,
                    updated_at = clock_timestamp();
        ELSE
            DELETE FROM operations.data_watermarks WHERE dataset_id = v_run.dataset_id AND partition_key = v_partition;
        END IF;
    END LOOP;
    RETURN v_changed;
END $$;
