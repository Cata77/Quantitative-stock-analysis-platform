package com.quantplatform.marketdata.operations;

import com.quantplatform.ingestion.ObservationEvent;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable work primitives. Provider calls and Kafka sends belong outside these transactions. */
@Service
public class IngestionRunStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public IngestionRunStore(DataSource dataSource, PlatformTransactionManager transactionManager,
                             ObjectMapper mapper) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
    }

    public UUID plan(IngestionPlan plan) {
        return Objects.requireNonNull(transactions.execute(transaction -> {
            jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", plan.logicalKey()).query(Integer.class).single();
            var existing = jdbc.sql("SELECT ingestion_run_id FROM operations.ingestion_runs WHERE logical_key = :key")
                    .param("key", plan.logicalKey()).query(UUID.class).optional();
            if (existing.isPresent()) {
                return existing.get();
            }
            var datasetId = jdbc.sql("""
                    SELECT job.dataset_id FROM operations.job_definitions job
                    JOIN operations.datasets dataset USING (dataset_id)
                    JOIN operations.data_providers provider USING (provider_id)
                    WHERE job.job_definition_id = :job AND job.active AND dataset.active AND provider.active
                    """).param("job", plan.jobDefinitionId()).query(UUID.class).optional()
                    .orElseThrow(() -> new IllegalArgumentException("job/provider/dataset is missing or inactive"));
            requireSnapshot(plan.sp500SnapshotId(), "SP500");
            requireSnapshot(plan.nasdaq100SnapshotId(), "NASDAQ100");
            int expected = jdbc.sql("""
                    SELECT COUNT(DISTINCT instrument_id) FROM reference.universe_memberships
                    WHERE universe_snapshot_id IN (:sp500, :nasdaq)
                    """).param("sp500", plan.sp500SnapshotId()).param("nasdaq", plan.nasdaq100SnapshotId())
                    .query(Integer.class).single();
            if (expected == 0) {
                throw new IllegalArgumentException("cannot plan an empty universe");
            }
            var runId = jdbc.sql("""
                    INSERT INTO operations.ingestion_runs
                        (logical_key, job_definition_id, dataset_id, run_mode, request_key,
                         window_start, window_end, expected_count, application_version)
                    VALUES (:key, :job, :dataset, :mode, :request, :start, :end, :expected, :version)
                    RETURNING ingestion_run_id
                    """).param("key", plan.logicalKey()).param("job", plan.jobDefinitionId())
                    .param("dataset", datasetId).param("mode", plan.mode()).param("request", plan.requestKey())
                    .param("start", plan.windowStart()).param("end", plan.windowEnd())
                    .param("expected", expected).param("version", plan.applicationVersion())
                    .query(UUID.class).single();
            jdbc.sql("""
                    INSERT INTO operations.ingestion_run_snapshots VALUES (:run, :sp500), (:run, :nasdaq)
                    """).param("run", runId).param("sp500", plan.sp500SnapshotId())
                    .param("nasdaq", plan.nasdaq100SnapshotId()).update();
            jdbc.sql("""
                    INSERT INTO operations.ingestion_run_items (ingestion_run_id, instrument_id, item_key)
                    SELECT :run, instrument_id, instrument_id::text FROM reference.universe_memberships
                    WHERE universe_snapshot_id IN (:sp500, :nasdaq) GROUP BY instrument_id
                    """).param("run", runId).param("sp500", plan.sp500SnapshotId())
                    .param("nasdaq", plan.nasdaq100SnapshotId()).update();
            return runId;
        }));
    }

    public List<JobLease> claim(UUID runId, String workerId, int limit, Duration leaseDuration) {
        requireDuration(leaseDuration);
        if (workerId == null || workerId.isBlank() || workerId.length() > 150 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("workerId and a limit between 1 and 1000 are required");
        }
        return Objects.requireNonNull(transactions.execute(transaction -> {
            var candidates = jdbc.sql("""
                    SELECT item.ingestion_run_item_id, item.attempt_count, job.max_attempts
                    FROM operations.ingestion_run_items item
                    JOIN operations.ingestion_runs run USING (ingestion_run_id)
                    JOIN operations.job_definitions job USING (job_definition_id)
                    JOIN operations.datasets dataset ON dataset.dataset_id = run.dataset_id
                    JOIN operations.data_providers provider USING (provider_id)
                    WHERE item.ingestion_run_id = :run AND job.active AND dataset.active AND provider.active
                      AND (item.status = 'PLANNED'
                        OR (item.status = 'WAITING_RETRY' AND item.next_retry_at <= clock_timestamp())
                        OR (item.status = 'RUNNING' AND item.lease_until <= clock_timestamp()))
                    ORDER BY item.created_at, item.ingestion_run_item_id
                    LIMIT :limit FOR UPDATE OF item SKIP LOCKED
                    """).param("run", runId).param("limit", limit).query((rs, row) -> new Candidate(
                            rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3))).list();
            var leases = new ArrayList<JobLease>();
            for (var candidate : candidates) {
                jdbc.sql("""
                        UPDATE operations.ingestion_attempts SET status = 'LEASE_EXPIRED', finished_at = clock_timestamp(),
                            failure = '{"code":"LEASE_EXPIRED"}'
                        WHERE ingestion_run_item_id = :item AND status = 'RUNNING'
                        """).param("item", candidate.itemId()).update();
                if (candidate.attempts() >= candidate.maxAttempts()) {
                    jdbc.sql("""
                            UPDATE operations.ingestion_run_items SET status = 'FAILED', lease_token = NULL,
                                lease_owner = NULL, lease_until = NULL, next_retry_at = NULL,
                                failure = '{"code":"ATTEMPTS_EXHAUSTED"}', updated_at = clock_timestamp()
                            WHERE ingestion_run_item_id = :item
                            """).param("item", candidate.itemId()).update();
                    continue;
                }
                var token = UUID.randomUUID();
                var lease = jdbc.sql("""
                        UPDATE operations.ingestion_run_items SET status = 'RUNNING',
                            lease_token = :token, lease_owner = :worker,
                            lease_until = clock_timestamp() + :millis * INTERVAL '1 millisecond',
                            attempt_count = attempt_count + 1, next_retry_at = NULL,
                            failure = NULL, updated_at = clock_timestamp()
                        WHERE ingestion_run_item_id = :item
                        RETURNING ingestion_run_item_id, ingestion_run_id, instrument_id, lease_token,
                            attempt_count, lease_until,
                            (SELECT checkpoint::text FROM operations.ingestion_checkpoints checkpoint
                             WHERE checkpoint.ingestion_run_item_id = operations.ingestion_run_items.ingestion_run_item_id)
                        """).param("token", token).param("worker", workerId)
                        .param("millis", leaseDuration.toMillis()).param("item", candidate.itemId())
                        .query((rs, row) -> new JobLease(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getInt(5),
                                rs.getTimestamp(6).toInstant(), rs.getString(7))).single();
                jdbc.sql("""
                        INSERT INTO operations.ingestion_attempts
                            (attempt_id, ingestion_run_item_id, attempt_number, worker_id, status)
                        VALUES (:token, :item, :number, :worker, 'RUNNING')
                        """).param("token", token).param("item", lease.itemId())
                        .param("number", lease.attemptNumber()).param("worker", workerId).update();
                leases.add(lease);
            }
            refreshRun(runId);
            return List.copyOf(leases);
        }));
    }

    public void retry(JobLease lease, String failureCode, String detail, Duration backoff) {
        requireDuration(backoff);
        if (failureCode == null || failureCode.isBlank() || detail == null) {
            throw new IllegalArgumentException("structured failure code and detail are required");
        }
        String failure = mapper.writeValueAsString(Map.of("code", failureCode, "detail", detail));
        transactions.executeWithoutResult(transaction -> {
            lockLease(lease);
            jdbc.sql("""
                    UPDATE operations.ingestion_run_items item SET
                        status = CASE WHEN item.attempt_count < job.max_attempts THEN 'WAITING_RETRY' ELSE 'FAILED' END,
                        next_retry_at = CASE WHEN item.attempt_count < job.max_attempts
                            THEN clock_timestamp() + :millis * INTERVAL '1 millisecond' END,
                        failure = CAST(:failure AS jsonb), lease_token = NULL, lease_owner = NULL, lease_until = NULL,
                        updated_at = clock_timestamp()
                    FROM operations.ingestion_runs run JOIN operations.job_definitions job USING (job_definition_id)
                    WHERE item.ingestion_run_id = run.ingestion_run_id AND item.ingestion_run_item_id = :item
                    """).param("item", lease.itemId()).param("millis", backoff.toMillis())
                    .param("failure", failure).update();
            finishAttempt(lease, "FAILED", failure);
            refreshRun(lease.runId());
        });
    }

    /** Persist a provider page and its next-page checkpoint atomically. Only the final page stages the item. */
    public List<UUID> stage(JobLease lease, SourceArtifact source, List<OutboxObservation> observations,
                            String checkpointJson, boolean lastPage) {
        Objects.requireNonNull(source, "source");
        var page = List.copyOf(observations);
        String checkpoint = ObservationIdentity.canonicalJson(mapper, checkpointJson);
        return Objects.requireNonNull(transactions.execute(transaction -> {
            UUID dataset = lockLease(lease);
            var artifact = jdbc.sql("""
                    INSERT INTO operations.source_artifacts
                        (dataset_id, request_key, source_uri, retrieved_at, content_hash, media_type, parser_version, inline_content)
                    VALUES (:dataset, :request, :uri, :retrieved, :hash, 'application/json', :parser, CAST(:raw AS jsonb))
                    ON CONFLICT (dataset_id, request_key, content_hash, parser_version)
                    DO UPDATE SET content_hash = EXCLUDED.content_hash
                    RETURNING source_artifact_id
                    """).param("dataset", dataset).param("request", source.requestKey()).param("uri", source.sourceUri())
                    .param("retrieved", source.retrievedAt().atOffset(ZoneOffset.UTC))
                    .param("hash", ObservationIdentity.contentHash(source.rawJson()))
                    .param("parser", source.parserVersion()).param("raw", source.rawJson()).query(UUID.class).single();
            jdbc.sql("""
                    INSERT INTO operations.ingestion_item_artifacts VALUES (:item, :artifact) ON CONFLICT DO NOTHING
                    """).param("item", lease.itemId()).param("artifact", artifact).update();
            var events = new ArrayList<UUID>();
            for (var observation : page) {
                var envelope = ObservationEvent.create(dataset, lease.instrumentId(), observation.eventType(),
                        observation.economicTime(), observation.adjustmentMode(), observation.payloadJson());
                var stored = jdbc.sql("""
                        INSERT INTO operations.outbox_events
                            (event_id, observation_key, dataset_id, instrument_id, source_artifact_id,
                             topic, partition_key, schema_version, payload, payload_hash)
                        VALUES (:event, :key, :dataset, :instrument, :artifact, :topic, :instrument, 2,
                            CAST(:payload AS jsonb), :hash)
                        ON CONFLICT (topic, observation_key) DO UPDATE SET observation_key = EXCLUDED.observation_key
                        RETURNING event_id
                        """).param("event", envelope.eventId()).param("key", envelope.observationKey()).param("dataset", dataset)
                        .param("instrument", lease.instrumentId()).param("artifact", artifact).param("topic", observation.topic())
                        .param("payload", envelope.json()).param("hash", envelope.payloadHash()).query(UUID.class).single();
                jdbc.sql("""
                        INSERT INTO operations.ingestion_item_events VALUES (:item, :event) ON CONFLICT DO NOTHING
                        """).param("item", lease.itemId()).param("event", stored).update();
                events.add(stored);
            }
            jdbc.sql("""
                    INSERT INTO operations.ingestion_checkpoints (ingestion_run_item_id, attempt_id, checkpoint)
                    VALUES (:item, :token, CAST(:checkpoint AS jsonb))
                    ON CONFLICT (ingestion_run_item_id) DO UPDATE SET attempt_id = EXCLUDED.attempt_id,
                        checkpoint = EXCLUDED.checkpoint, updated_at = clock_timestamp()
                    """).param("item", lease.itemId()).param("token", lease.token()).param("checkpoint", checkpoint).update();
            if (lastPage) {
                jdbc.sql("""
                        UPDATE operations.ingestion_run_items SET status = 'STAGED', lease_token = NULL,
                            lease_owner = NULL, lease_until = NULL, updated_at = clock_timestamp()
                        WHERE ingestion_run_item_id = :item
                        """).param("item", lease.itemId()).update();
                finishAttempt(lease, "STAGED", null);
            }
            refreshRun(lease.runId());
            return List.copyOf(events);
        }));
    }

    private void requireSnapshot(UUID snapshot, String code) {
        boolean valid = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM reference.universe_snapshots snapshot JOIN reference.universes universe USING (universe_id)
                    WHERE snapshot.universe_snapshot_id = :id AND universe.code = :code
                      AND snapshot.completeness_status = 'COMPLETE'
                      AND snapshot.expected_member_count = snapshot.imported_member_count
                      AND snapshot.imported_member_count = (SELECT COUNT(*) FROM reference.universe_memberships membership
                          WHERE membership.universe_snapshot_id = snapshot.universe_snapshot_id)
                )
                """).param("id", snapshot).param("code", code).query(Boolean.class).single();
        if (!valid) {
            throw new IllegalArgumentException("a complete, reconciled " + code + " snapshot is required");
        }
    }

    public void renew(JobLease lease, Duration duration) {
        requireDuration(duration);
        transactions.executeWithoutResult(transaction -> {
            lockLease(lease);
            jdbc.sql("""
                    UPDATE operations.ingestion_run_items
                    SET lease_until = clock_timestamp() + :millis * INTERVAL '1 millisecond'
                    WHERE ingestion_run_item_id = :item
                    """).param("millis", duration.toMillis()).param("item", lease.itemId()).update();
        });
    }

    private UUID lockLease(JobLease lease) {
        return jdbc.sql("""
                SELECT run.dataset_id FROM operations.ingestion_run_items item
                JOIN operations.ingestion_runs run USING (ingestion_run_id)
                WHERE item.ingestion_run_item_id = :item AND item.ingestion_run_id = :run
                  AND item.instrument_id = :instrument AND item.lease_token = :token
                  AND item.status = 'RUNNING' AND item.lease_until > clock_timestamp()
                FOR UPDATE OF item
                """).param("item", lease.itemId()).param("run", lease.runId())
                .param("instrument", lease.instrumentId()).param("token", lease.token())
                .query(UUID.class).optional().orElseThrow(() -> new IllegalStateException("job lease expired or was replaced"));
    }

    private void finishAttempt(JobLease lease, String status, String failure) {
        jdbc.sql("""
                UPDATE operations.ingestion_attempts SET status = :status,
                    finished_at = clock_timestamp(), failure = CAST(:failure AS jsonb)
                WHERE attempt_id = :token
                """).param("status", status).param("failure", failure).param("token", lease.token()).update();
    }

    private void refreshRun(UUID runId) {
        // Acquire the run lock in its own statement, then count using a fresh READ COMMITTED snapshot.
        jdbc.sql("SELECT ingestion_run_id FROM operations.ingestion_runs WHERE ingestion_run_id = :run FOR UPDATE")
                .param("run", runId).query(UUID.class).single();
        jdbc.sql("""
                UPDATE operations.ingestion_runs run SET
                    staged_count = progress.staged, accepted_count = progress.accepted, failed_count = progress.failed,
                    status = CASE
                        WHEN progress.failed > 0 THEN 'FAILED'
                        WHEN progress.accepted = run.expected_count THEN 'COMPLETE'
                        WHEN progress.staged = run.expected_count THEN 'VALIDATING'
                        WHEN progress.running > 0 THEN 'RUNNING'
                        WHEN progress.retrying > 0 THEN 'WAITING_RETRY'
                        ELSE 'PLANNED' END,
                    started_at = CASE WHEN progress.attempted > 0 THEN COALESCE(run.started_at, clock_timestamp()) END,
                    completed_at = CASE WHEN progress.accepted = run.expected_count THEN COALESCE(run.completed_at, clock_timestamp()) END,
                    updated_at = clock_timestamp()
                FROM (
                    SELECT COUNT(*) FILTER (WHERE status IN ('STAGED', 'COMPLETE')) AS staged,
                        COUNT(*) FILTER (WHERE status = 'COMPLETE') AS accepted,
                        COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                        COUNT(*) FILTER (WHERE status = 'RUNNING') AS running,
                        COUNT(*) FILTER (WHERE status = 'WAITING_RETRY') AS retrying,
                        COUNT(*) FILTER (WHERE attempt_count > 0) AS attempted
                    FROM operations.ingestion_run_items WHERE ingestion_run_id = :run
                ) progress WHERE run.ingestion_run_id = :run
                """).param("run", runId).update();
    }

    private static void requireDuration(Duration duration) {
        if (duration == null || duration.toMillis() < 1 || duration.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("duration must be between 1 millisecond and 1 day");
        }
    }

    private record Candidate(UUID itemId, int attempts, int maxAttempts) {
    }
}
