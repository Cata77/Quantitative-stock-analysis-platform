package com.quantplatform.marketdata.operations;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import com.quantplatform.ingestion.CanonicalJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OutboxStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final DeliveryProperties properties;

    public OutboxStore(DataSource source, PlatformTransactionManager manager, DeliveryProperties properties) {
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(manager);
        this.properties = properties;
    }

    public Optional<Claim> claim(String worker) {
        return transactions.execute(transaction -> {
            jdbc.sql("""
                    WITH exhausted AS (
                        SELECT event_id FROM operations.outbox_events
                        WHERE attempt_count >= :max AND (status IN ('PENDING','FAILED_RETRYABLE')
                            OR (status = 'CLAIMED' AND lease_until <= clock_timestamp()))
                        FOR UPDATE SKIP LOCKED
                    ) UPDATE operations.outbox_events o SET status = 'FAILED_TERMINAL',
                        lease_token = NULL, lease_owner = NULL, lease_until = NULL, next_retry_at = NULL,
                        failure = '{"code":"DELIVERY_ATTEMPTS_EXHAUSTED"}', updated_at = clock_timestamp()
                    FROM exhausted WHERE o.event_id = exhausted.event_id
                    """).param("max", properties.maxAttempts()).update();
            return jdbc.sql("""
                    WITH candidate AS (
                        SELECT event_id FROM operations.outbox_events
                        WHERE attempt_count < :max AND (status = 'PENDING'
                            OR (status = 'FAILED_RETRYABLE' AND next_retry_at <= clock_timestamp())
                            OR (status = 'CLAIMED' AND lease_until <= clock_timestamp()))
                        ORDER BY created_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    ) UPDATE operations.outbox_events o SET status = 'CLAIMED', lease_token = :token,
                        lease_owner = :worker, lease_until = clock_timestamp() + :lease * INTERVAL '1 millisecond',
                        next_retry_at = NULL, attempt_count = attempt_count + 1, failure = NULL, updated_at = clock_timestamp()
                    FROM candidate WHERE o.event_id = candidate.event_id
                    RETURNING o.event_id, o.lease_token, o.topic, o.partition_key, o.payload::text, o.payload_hash, o.attempt_count
                    """).param("max", properties.maxAttempts()).param("token", UUID.randomUUID()).param("worker", worker)
                    .param("lease", properties.lease().toMillis()).query((rs, row) -> new Claim(rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getInt(7))).optional();
        });
    }

    public boolean acknowledge(Claim claim, int partition, long offset) {
        return jdbc.sql("""
                UPDATE operations.outbox_events SET status = 'PUBLISHED', broker_partition = :partition,
                    broker_offset = :offset, published_at = clock_timestamp(), lease_token = NULL,
                    lease_owner = NULL, lease_until = NULL, updated_at = clock_timestamp()
                WHERE event_id = :event AND lease_token = :token AND status = 'CLAIMED' AND lease_until > clock_timestamp()
                """).param("partition", partition).param("offset", offset).param("event", claim.eventId())
                .param("token", claim.token()).update() == 1;
    }

    public void failed(Claim claim, String code) {
        boolean terminal = claim.attempt() >= properties.maxAttempts();
        long delay = properties.retryBackoff().multipliedBy(1L << Math.min(claim.attempt() - 1, 8)).toMillis();
        jdbc.sql("""
                UPDATE operations.outbox_events SET status = :status, failure = CAST(:failure AS jsonb),
                    next_retry_at = CASE WHEN :terminal THEN NULL ELSE clock_timestamp() + :delay * INTERVAL '1 millisecond' END,
                    lease_token = NULL, lease_owner = NULL, lease_until = NULL, updated_at = clock_timestamp()
                WHERE event_id = :event AND lease_token = :token AND status = 'CLAIMED'
                """).param("status", terminal ? "FAILED_TERMINAL" : "FAILED_RETRYABLE")
                .param("failure", CanonicalJson.write(Map.of("code", code))).param("terminal", terminal)
                .param("delay", delay).param("event", claim.eventId()).param("token", claim.token()).update();
    }

    public record Claim(UUID eventId, UUID token, String topic, String key, String json, String hash, int attempt) { }
}
