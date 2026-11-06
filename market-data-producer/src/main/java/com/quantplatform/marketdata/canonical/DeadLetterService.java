package com.quantplatform.marketdata.canonical;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.ingestion.ObservationEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Quarantine of observations that cannot become canonical data (P2s: PostgreSQL only).
 *
 * <p>The dead-letter record keeps the exact bytes, the payload hash and the reason. A replay is an
 * explicit, audited operator command: it returns the dead-lettered outbox event to {@code PENDING},
 * so the collector's delivery loop processes the original observation again, through the inbox.
 */
public class DeadLetterService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final String inputTopic;
    private final String consumer;

    public DeadLetterService(DataSource source, PlatformTransactionManager manager, String inputTopic, String consumer) {
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(manager);
        this.inputTopic = inputTopic;
        this.consumer = consumer;
    }

    public UUID recover(UUID eventId, String topic, int partition, long offset, String key, String json, String hash,
            Exception failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String detail = failure.getMessage() == null ? "No detail supplied" : failure.getMessage();
        String reason = failure.getClass().getSimpleName() + ": " + cause.getClass().getSimpleName()
                + ": " + detail.substring(0, Math.min(detail.length(), 500));
        var headers = List.of(header(ObservationEvent.HASH_HEADER, hash), header("x-outbox-event-id", eventId.toString()));
        return transactions.execute(status -> jdbc.sql("""
                INSERT INTO operations.dead_letter_records (consumer_name, source_topic, source_partition,
                    source_offset, record_key, payload, headers, reason)
                VALUES (:consumer, :topic, :partition, :offset, :key, :payload, CAST(:headers AS jsonb), :reason)
                ON CONFLICT (consumer_name, source_topic, source_partition, source_offset)
                DO UPDATE SET reason = EXCLUDED.reason RETURNING dead_letter_id
                """).param("consumer", consumer).param("topic", topic).param("partition", partition)
                .param("offset", offset).param("key", key)
                .param("payload", json == null ? null : json.getBytes(StandardCharsets.UTF_8))
                .param("headers", CanonicalJson.write(headers)).param("reason", reason).query(UUID.class).single());
    }

    public List<Inspection> inspect(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("inspection limit must be 1..1000");
        return jdbc.sql("""
                SELECT dead_letter_id, source_topic, source_partition, source_offset, record_key,
                    encode(payload, 'base64') payload_base64, headers::text, reason
                FROM operations.dead_letter_records WHERE consumer_name = :consumer
                ORDER BY detected_at DESC, dead_letter_id LIMIT :limit
                """).param("consumer", consumer).param("limit", limit).query((rs, row) -> new Inspection(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8))).list();
    }

    /** Reuse replayId when retrying a command whose acknowledgement was lost. */
    public boolean replay(UUID id, UUID replayId, String operator, String reason) {
        if (id == null || replayId == null || operator == null || operator.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("replay requires entry ID, stable request ID, operator, and reason");
        }
        var entry = get(id);
        if (!entry.topic().equals(inputTopic)) throw new IllegalArgumentException("replay target is not the configured input topic");
        UUID eventId = eventId(entry);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            jdbc.sql("""
                    INSERT INTO operations.dead_letter_replays (replay_id, dead_letter_id, operator_name, reason)
                    VALUES (:replay, :id, :operator, :reason) ON CONFLICT (replay_id) DO NOTHING
                    """).param("replay", replayId).param("id", id).param("operator", operator).param("reason", reason).update();
            var current = jdbc.sql("""
                    SELECT dead_letter_id = :id AND operator_name = :operator AND reason = :reason, status
                    FROM operations.dead_letter_replays WHERE replay_id = :replay FOR UPDATE
                    """).param("id", id).param("operator", operator).param("reason", reason).param("replay", replayId)
                    .query((rs, n) -> Map.entry(rs.getBoolean(1), rs.getString(2))).single();
            if (!current.getKey()) throw new IllegalArgumentException("replay request ID already belongs to a different command");
            if ("PUBLISHED".equals(current.getValue())) return true;
            int reset = jdbc.sql("""
                    UPDATE operations.outbox_events SET status = 'PENDING', attempt_count = 0, failure = NULL,
                        next_retry_at = NULL, updated_at = clock_timestamp()
                    WHERE event_id = :event AND topic = :topic AND status = 'FAILED_TERMINAL'
                    """).param("event", eventId).param("topic", entry.topic()).update();
            if (reset != 1) throw new IllegalArgumentException("dead-lettered outbox event is not available for replay");
            return jdbc.sql("""
                    UPDATE operations.dead_letter_replays SET status = 'PUBLISHED', published_at = clock_timestamp(),
                        broker_partition = 0, broker_offset = (extract(epoch FROM clock_timestamp()) * 1000000)::bigint,
                        attempt_count = attempt_count + 1
                    WHERE replay_id = :replay AND status = 'PENDING'
                    """).param("replay", replayId).update() == 1;
        }));
    }

    private UUID eventId(Entry entry) {
        for (Object value : CanonicalJson.MAPPER.readValue(entry.headers(), List.class)) {
            Map<?, ?> header = (Map<?, ?>) value;
            if ("x-outbox-event-id".equals(header.get("name")) && header.get("value") != null)
                return UUID.fromString(new String(Base64.getDecoder().decode(header.get("value").toString()), StandardCharsets.UTF_8));
        }
        try {
            return ObservationEvent.parse(entry.key(), new String(entry.payload(), StandardCharsets.UTF_8)).eventId();
        } catch (RuntimeException unreadable) {
            throw new IllegalArgumentException("dead-letter entry does not identify an outbox event", unreadable);
        }
    }

    private Entry get(UUID id) {
        return jdbc.sql("""
                SELECT source_topic, record_key, payload, headers::text
                FROM operations.dead_letter_records WHERE dead_letter_id = :id AND consumer_name = :consumer
                """).param("id", id).param("consumer", consumer).query((rs, row) -> new Entry(rs.getString(1), rs.getString(2),
                        rs.getBytes(3), rs.getString(4))).optional()
                .orElseThrow(() -> new IllegalArgumentException("unknown dead-letter entry for this consumer"));
    }

    private static Map<String, Object> header(String name, String value) {
        return Map.of("name", name, "value", Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record Entry(String topic, String key, byte[] payload, String headers) { }
    public record Inspection(UUID id, String topic, int partition, long offset, String key,
                             String payloadBase64, String headers, String reason) { }
}
