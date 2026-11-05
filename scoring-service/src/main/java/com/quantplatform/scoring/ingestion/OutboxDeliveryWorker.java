package com.quantplatform.scoring.ingestion;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.ingestion.ObservationEvent;
import com.quantplatform.scoring.config.ScoringProperties;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Direct PostgreSQL delivery of the producer's outbox (P2s, replaces Kafka).
 *
 * <p>An event is claimed with a lease ({@code FOR UPDATE SKIP LOCKED}), processed by the same
 * {@link DurableObservationProcessor} transaction (inbox + canonical data), then acknowledged.
 * A crash between the canonical commit and the acknowledgement only re-delivers the event after
 * the lease expires; the inbox makes the second delivery a no-op, as with Kafka redelivery.
 * Events of one instrument are delivered in creation order: an instrument whose earlier event is
 * still pending or retrying is not claimed (the guarantee Kafka gave per partition key).
 * Invalid events are dead-lettered once; transient failures retry with exponential backoff and
 * are dead-lettered when the attempts are exhausted.
 */
@Component
@ConditionalOnProperty(name = "scoring.delivery.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDeliveryWorker {
    /** Logical partition recorded in the inbox; offsets are per-delivery surrogates (no broker). */
    static final int PARTITION = 0;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final DurableObservationProcessor processor;
    private final DeadLetterService deadLetters;
    private final ScoringProperties properties;
    private final Duration lease;
    private final String worker = "outbox-" + UUID.randomUUID();

    public OutboxDeliveryWorker(DataSource source, PlatformTransactionManager manager,
            DurableObservationProcessor processor, DeadLetterService deadLetters, ScoringProperties properties,
            @Value("${scoring.delivery.lease:PT2M}") Duration lease) {
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(manager);
        this.processor = processor;
        this.deadLetters = deadLetters;
        this.properties = properties;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${scoring.delivery.interval-ms:1000}", initialDelayString = "${scoring.delivery.interval-ms:1000}")
    public void poll() {
        try { drain(500); }
        catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("Outbox delivery will retry: {}", failure.getClass().getSimpleName());
        }
    }

    /** Deliver up to {@code limit} events; returns how many were accepted for the first time. */
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
            if (!CanonicalJson.sha256(CanonicalJson.normalize(claim.json())).equals(claim.hash()))
                throw new IllegalArgumentException("payload hash is missing or invalid");
            event = ObservationEvent.parse(claim.key(), claim.json());
            if (!event.eventId().equals(claim.eventId())) throw new IllegalArgumentException("event identity differs from outbox row");
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
            if (claim.attempt() > properties.retryAttempts()) deadLetter(claim, transientFailure);
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
            """).param("topic", properties.inputTopic()).param("token", UUID.randomUUID()).param("worker", worker)
            .param("lease", lease.toMillis())
            .query((rs, row) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getLong(8))).optional());
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
        long delay = properties.retryBackoff().multipliedBy(1L << Math.min(claim.attempt() - 1, 8)).toMillis();
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

    record Claim(UUID eventId, UUID token, String topic, String key, String json, String hash, int attempt, long offset) { }
}
