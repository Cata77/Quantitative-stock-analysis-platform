-- CURRENT snapshots may exclude a member whose provider response explicitly lacks
-- the session. This does not certify complete price coverage or resolve its issue.
WITH members AS (
    SELECT DISTINCT instrument_id FROM reference.universe_memberships
    WHERE universe_snapshot_id IN (:sp,:nq)
), required AS (
    SELECT * FROM (VALUES(CAST(:raw AS uuid),'RAW'),
        (CAST(:adjusted AS uuid),'SPLIT_DIVIDEND')) v(dataset,mode)
), candidates AS (
    SELECT r.ingestion_run_id,r.dataset_id,j.configuration,required.mode
    FROM required JOIN operations.ingestion_runs r ON r.dataset_id=required.dataset
    JOIN operations.job_definitions j USING(job_definition_id)
    WHERE r.window_start=:date AND r.window_end=:date
      AND r.expected_count=(SELECT count(*) FROM members)
      AND j.configuration->>'eventType'='DAILY_PRICE'
      AND j.configuration->>'feed'='sip'
      AND j.configuration->>'adjustment'=required.mode
      AND (required.mode='RAW' OR j.configuration->>'adjustmentAsOf'=CAST(:basis AS text))
      AND EXISTS(SELECT 1 FROM operations.ingestion_run_snapshots s
          WHERE s.ingestion_run_id=r.ingestion_run_id AND s.universe_snapshot_id=:sp)
      AND EXISTS(SELECT 1 FROM operations.ingestion_run_snapshots s
          WHERE s.ingestion_run_id=r.ingestion_run_id AND s.universe_snapshot_id=:nq)
      AND NOT EXISTS(SELECT 1 FROM operations.data_coverage c
          WHERE c.ingestion_run_id=r.ingestion_run_id AND NOT c.valid)
      AND NOT EXISTS(SELECT 1 FROM operations.data_quality_issues q
          WHERE q.dataset_id=r.dataset_id AND q.status='OPEN' AND q.severity='BLOCKING'
            AND (q.ingestion_run_id IS NULL OR q.ingestion_run_id=r.ingestion_run_id)
            AND q.issue_type<>'MISSING_SESSION')
), outcomes AS (
    SELECT c.*,m.instrument_id,i.ingestion_run_item_id,i.status,
        CASE WHEN i.status='COMPLETE' THEN jsonb_build_object('outcome','ACCEPTED')
        WHEN i.status IN ('FAILED','WAITING_RETRY') AND i.failure->>'code'='MISSING_SESSION'
          AND i.updated_at<=:cutoff AND cp.updated_at<=:cutoff
          AND cp.checkpoint->>'nextPageToken'='' AND cp.checkpoint->>'barsSeen'='0'
          AND a.status='FAILED' AND a.failure->>'code'='MISSING_SESSION'
          AND a.finished_at<=:cutoff
          AND EXISTS(SELECT 1 FROM operations.data_quality_issues q
              WHERE q.ingestion_run_id=c.ingestion_run_id AND q.instrument_id=m.instrument_id
                AND q.issue_type='MISSING_SESSION' AND q.affected_key=CAST(:date AS text))
          AND NOT EXISTS(SELECT 1 FROM operations.ingestion_item_events e
              WHERE e.ingestion_run_item_id=i.ingestion_run_item_id)
          AND EXISTS(SELECT 1 FROM operations.ingestion_item_artifacts ia
              JOIN operations.source_artifacts s USING(source_artifact_id)
              WHERE ia.ingestion_run_item_id=i.ingestion_run_item_id
                AND s.dataset_id=c.dataset_id AND s.retrieved_at<=:cutoff
                AND s.parser_version='alpaca-daily-v2' AND s.inline_content IS NOT NULL)
        THEN jsonb_build_object('outcome','PROVIDER_SESSION_ABSENT',
            'attemptId',a.attempt_id,'checkpoint',cp.checkpoint,
            'observedAt',a.finished_at,'sources',(
                SELECT jsonb_agg(jsonb_build_object('id',s.source_artifact_id,'hash',s.content_hash))
                FROM operations.ingestion_item_artifacts ia
                JOIN operations.source_artifacts s USING(source_artifact_id)
                WHERE ia.ingestion_run_item_id=i.ingestion_run_item_id AND s.retrieved_at<=:cutoff))
        END evidence
    FROM candidates c CROSS JOIN members m
    LEFT JOIN operations.ingestion_run_items i
        ON i.ingestion_run_id=c.ingestion_run_id AND i.instrument_id=m.instrument_id
    LEFT JOIN operations.ingestion_checkpoints cp USING(ingestion_run_item_id)
    LEFT JOIN operations.ingestion_attempts a ON a.attempt_id=cp.attempt_id
), ready AS (
    SELECT ingestion_run_id,mode,jsonb_agg(jsonb_build_object(
        'instrumentId',instrument_id,'itemId',ingestion_run_item_id,'evidence',evidence)
        ORDER BY instrument_id) items
    FROM outcomes GROUP BY ingestion_run_id,mode HAVING bool_and(evidence IS NOT NULL)
), chosen AS (
    SELECT DISTINCT ON(mode) * FROM ready ORDER BY mode,ingestion_run_id
)
SELECT CASE WHEN count(*)=2 THEN jsonb_build_object('policy','CURRENT_COLLECTION_OUTCOMES_V1',
    'runs',jsonb_agg(jsonb_build_object('runId',ingestion_run_id,'mode',mode,'items',items) ORDER BY mode))::text
    ELSE NULL END FROM chosen
