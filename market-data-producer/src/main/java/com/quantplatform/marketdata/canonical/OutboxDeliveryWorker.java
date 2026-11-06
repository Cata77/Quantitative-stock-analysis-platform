package com.quantplatform.marketdata.canonical;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.ingestion.ObservationEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acceptance of staged observations into canonical data (P2s, stage B: inside the collector).
 *
 * <p>{@link #deliverStaged} runs in the staging transaction of a provider page: source artifact,
 * outbox row, inbox receipt and canonical rows commit together, so there is no hand-off between
 * processes. An invalid observation is quarantined under a savepoint (dead-letter record and
 * {@code FAILED_TERMINAL}) without losing the rest of the page; any other failure rolls the whole
 * page back and the item is retried through its audited ingestion attempts.
 *
 * <p>{@link #drain} delivers what is still {@code PENDING}: events staged before the upgrade,
 * audited dead-letter replays, and events that waited for an earlier event of the same instrument.
 * It keeps the former worker's guarantees: lease with {@code FOR UPDATE SKIP LOCKED}, creation order
 * per instrument, bounded retry with exponential backoff, then dead letter. The inbox makes a second
 * delivery of the same observation a no-op.
 */
public class OutboxDeliveryWorker {
    /** Logical partition recorded in the inbox; offsets are per-delivery surrogates (no broker). */
    static final int PARTITION = 0;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate savepoint;
    private final DurableObservationProcessor processor;
    private final DeadLetterService deadLetters;
    private final String topic;
    private final int retryAttempts;
    private final Duration retryBackoff;
    private final Duration lease;
    private final String worker = "canonical-" + UUID.randomUUID();

    public OutboxDeliveryWorker(DataSource source, PlatformTransactionManager manager,
            DurableObservationProcessor processor, DeadLetterService deadLetters, String topic,
            int retryAttempts, Duration retryBackoff, Duration lease) {
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(manager);
        savepoint = new TransactionTemplate(manager);
        savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.processor = processor;
        this.deadLetters = deadLetters;
        this.topic = topic;
        this.retryAttempts = retryAttempts;
        this.retryBackoff = retryBackoff;
        this.lease = lease;
    }

    public DurableObservationProcessor processor() { return processor; }

    /**
     * Accept freshly staged events in the caller's transaction. Events that are not {@code PENDING}
     * (already accepted, quarantined, or claimed) and events queued behind an earlier unfinished event
     * of the same instrument are left to {@link #drain}. Returns the number accepted for the first time.
     */
    public int deliverStaged(List<UUID> eventIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("staged events are accepted inside the staging transaction");
        }
        int accepted = 0;
        if (eventIds.isEmpty()) return 0;
        // Rows staged in one transaction share created_at: follow the drain's (created_at, event_id)
        // order, so each event finds its predecessors of the same instrument already accepted.
        var ordered = jdbc.sql("""
                SELECT event_id FROM operations.outbox_events WHERE event_id IN (:ids) ORDER BY created_at, event_id
                """).param("ids", eventIds).query(UUID.class).list();
        for (UUID id : ordered) {
            var row = jdbc.sql("""
                    SELECT o.event_id, o.topic, o.partition_key::text, o.payload::text, o.payload_hash
                    FROM operations.outbox_events o WHERE o.event_id = :id AND o.status = 'PENDING'
                      AND NOT EXISTS (SELECT 1 FROM operations.outbox_events earlier
                          WHERE earlier.topic = o.topic AND earlier.partition_key = o.partition_key
                            AND earlier.status IN ('PENDING','CLAIMED','FAILED_RETRYABLE')
                            AND (earlier.created_at, earlier.event_id) < (o.created_at, o.event_id))
                    FOR UPDATE OF o
                    """).param("id", id).query((rs, n) -> new Claim(rs.getObject(1, UUID.class), null, rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5), 0, 0)).optional();
            if (row.isEmpty()) continue;
            long offset = offset();
            var found = row.get();
            var claim = new Claim(found.eventId(), null, found.topic(), found.key(), found.json(), found.hash(), 0, offset);
            final ObservationEvent event;
            try {
                event = envelope(claim);
            } catch (Exception invalid) {
                quarantine(claim, new MarketDataValidationException("invalid observation envelope", invalid));
                continue;
            }
            try {
                boolean first = Boolean.TRUE.equals(savepoint.execute(status ->
                        processor.process(event, claim.topic(), PARTITION, claim.offset())));
                jdbc.sql("""
                        UPDATE operations.outbox_events SET status = 'PUBLISHED', broker_partition = :partition,
                            broker_offset = :offset, published_at = clock_timestamp(), attempt_count = attempt_count + 1,
                            updated_at = clock_timestamp()
                        WHERE event_id = :event AND status = 'PENDING'
                        """).param("partition", PARTITION).param("offset", claim.offset()).param("event", claim.eventId()).update();
                if (first) accepted++;
            } catch (MarketDataValidationException invalid) {
                quarantine(claim, invalid);
            }
        }
        return accepted;
    }

    /** Deliver up to {@code limit} pending events; returns how many were accepted for the first time. */
    public int drain(int limit) {
        int accepted = 0;
        for (int i = 0; i < limit && !Thread.currentThread().isInterrupted(); i++) {
            var claim = claim();
            if (claim.isEmpty()) break;
            if (deliver(claim.get())) accepted++;
        }
        return accepted;
    }

    boolean deliver(Claim claim) {
        final ObservationEvent event;
        try {
            event = envelope(claim);
        } catch (Exception invalid) {
            deadLetter(claim, new MarketDataValidationException("invalid observation envelope", invalid));
            return false;
        }
        try {
            boolean first = processor.process(event, claim.topic(), PARTITION, claim.offset());
            acknowledge(claim);
            return first;
        } catch (MarketDataValidationException invalid) {
            deadLetter(claim, invalid);
            return false;
        } catch (RuntimeException transientFailure) {
            if (claim.attempt() > retryAttempts) deadLetter(claim, transientFailure);
            else retry(claim, transientFailure);
            return false;
        }
    }

    Optional<Claim> claim() {
        return transactions.execute(status -> jdbc.sql("""
            WITH candidate AS (
                SELECT o.event_id FROM operations.outbox_events o
                WHERE o.topic = :topic AND (o.status = 'PENDING'
                    OR (o.status = 'FAILED_RETRYABLE' AND o.next_retry_at <= clock_timestamp())
                    OR (o.status = 'CLAIMED' AND o.lease_until <= clock_timestamp()))
                  AND NOT EXISTS (SELECT 1 FROM operations.outbox_events earlier
                      WHERE earlier.topic = o.topic AND earlier.partition_key = o.partition_key
                        AND earlier.status IN ('PENDING','CLAIMED','FAILED_RETRYABLE')
                        AND (earlier.created_at, earlier.event_id) < (o.created_at, o.event_id))
                ORDER BY o.created_at, o.event_id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
            ) UPDATE operations.outbox_events o SET status = 'CLAIMED', lease_token = :token,
                lease_owner = :worker, lease_until = clock_timestamp() + :lease * INTERVAL '1 millisecond',
                next_retry_at = NULL, attempt_count = attempt_count + 1, failure = NULL, updated_at = clock_timestamp()
            FROM candidate WHERE o.event_id = candidate.event_id
            RETURNING o.event_id, o.lease_token, o.topic, o.partition_key::text, o.payload::text, o.payload_hash,
                o.attempt_count, (extract(epoch FROM clock_timestamp()) * 1000000)::bigint
            """).param("topic", topic).param("token", UUID.randomUUID()).param("worker", worker)
            .param("lease", lease.toMillis())
            .query((rs, row) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getLong(8))).optional());
    }

    private ObservationEvent envelope(Claim claim) {
        if (!CanonicalJson.sha256(CanonicalJson.normalize(claim.json())).equals(claim.hash()))
            throw new IllegalArgumentException("payload hash is missing or invalid");
        var event = ObservationEvent.parse(claim.key(), claim.json());
        if (!event.eventId().equals(claim.eventId())) throw new IllegalArgumentException("event identity differs from outbox row");
        return event;
    }

    private long offset() {
        return jdbc.sql("SELECT (extract(epoch FROM clock_timestamp()) * 1000000)::bigint").query(Long.class).single();
    }

    private void acknowledge(Claim claim) {
        jdbc.sql("""
            UPDATE operations.outbox_events SET status = 'PUBLISHED', broker_partition = :partition,
                broker_offset = :offset, published_at = clock_timestamp(), lease_token = NULL, lease_owner = NULL,
                lease_until = NULL, updated_at = clock_timestamp()
            WHERE event_id = :event AND lease_token = :token AND status = 'CLAIMED'
            """).param("partition", PARTITION).param("offset", claim.offset()).param("event", claim.eventId())
            .param("token", claim.token()).update();
    }

    private void retry(Claim claim, RuntimeException failure) {
        long delay = retryBackoff.multipliedBy(1L << Math.min(claim.attempt() - 1, 8)).toMillis();
        jdbc.sql("""
            UPDATE operations.outbox_events SET status = 'FAILED_RETRYABLE', failure = CAST(:failure AS jsonb),
                next_retry_at = clock_timestamp() + :delay * INTERVAL '1 millisecond', lease_token = NULL,
                lease_owner = NULL, lease_until = NULL, updated_at = clock_timestamp()
            WHERE event_id = :event AND lease_token = :token AND status = 'CLAIMED'
            """).param("failure", CanonicalJson.write(Map.of("code", "CANONICAL_PROCESSING_FAILED",
                    "type", failure.getClass().getSimpleName()))).param("delay", delay)
            .param("event", claim.eventId()).param("token", claim.token()).update();
    }

    private void deadLetter(Claim claim, Exception failure) {
        deadLetters.recover(claim.eventId(), claim.topic(), PARTITION, claim.offset(), claim.key(), claim.json(),
                claim.hash(), failure);
        jdbc.sql("""
            UPDATE operations.outbox_events SET status = 'FAILED_TERMINAL', failure = CAST(:failure AS jsonb),
                next_retry_at = NULL, lease_token = NULL, lease_owner = NULL, lease_until = NULL,
                updated_at = clock_timestamp()
            WHERE event_id = :event AND lease_token = :token AND status = 'CLAIMED'
            """).param("failure", CanonicalJson.write(Map.of("code", "DEAD_LETTERED",
                    "type", failure.getClass().getSimpleName()))).param("event", claim.eventId())
            .param("token", claim.token()).update();
    }

    /** Dead letter of a staged (unclaimed) event, inside the staging transaction. */
    private void quarantine(Claim claim, Exception failure) {
        deadLetters.recover(claim.eventId(), claim.topic(), PARTITION, claim.offset(), claim.key(), claim.json(),
                claim.hash(), failure);
        jdbc.sql("""
            UPDATE operations.outbox_events SET status = 'FAILED_TERMINAL', failure = CAST(:failure AS jsonb),
                attempt_count = attempt_count + 1, updated_at = clock_timestamp()
            WHERE event_id = :event AND status = 'PENDING'
            """).param("failure", CanonicalJson.write(Map.of("code", "DEAD_LETTERED",
                    "type", failure.getClass().getSimpleName()))).param("event", claim.eventId()).update();
    }

    record Claim(UUID eventId, UUID token, String topic, String key, String json, String hash, int attempt, long offset) { }
}
