package com.quantplatform.scoring.ingestion;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.scoring.config.ScoringProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DeadLetterService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final KafkaTemplate<String, byte[]> kafka;
    private final ScoringProperties properties;
    private final String consumer;

    public DeadLetterService(DataSource source, PlatformTransactionManager manager, KafkaTemplate<String, byte[]> kafka,
            ScoringProperties properties, @Value("${spring.kafka.consumer.group-id}") String consumer) {
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(manager);
        this.kafka = kafka;
        this.properties = properties;
        this.consumer = consumer;
    }

    public void recover(ConsumerRecord<?, ?> record, Exception failure) {
        byte[] bytes = record.value() == null ? null : record.value() instanceof byte[] value
                ? value : record.value().toString().getBytes(StandardCharsets.UTF_8);
        var headers = new ArrayList<Map<String, Object>>();
        record.headers().forEach(header -> {
            var data = new LinkedHashMap<String, Object>();
            data.put("name", header.key());
            data.put("value", header.value() == null ? null : Base64.getEncoder().encodeToString(header.value()));
            headers.add(data);
        });
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String detail = failure.getMessage() == null ? "No detail supplied" : failure.getMessage();
        String reason = failure.getClass().getSimpleName() + ": " + cause.getClass().getSimpleName()
                + ": " + detail.substring(0, Math.min(detail.length(), 500));
        UUID id = transactions.execute(status -> jdbc.sql("""
                INSERT INTO operations.dead_letter_records (consumer_name, source_topic, source_partition,
                    source_offset, record_key, payload, headers, reason)
                VALUES (:consumer, :topic, :partition, :offset, :key, :payload, CAST(:headers AS jsonb), :reason)
                ON CONFLICT (consumer_name, source_topic, source_partition, source_offset)
                DO UPDATE SET reason = EXCLUDED.reason RETURNING dead_letter_id
                """).param("consumer", consumer).param("topic", record.topic()).param("partition", record.partition())
                .param("offset", record.offset()).param("key", record.key() == null ? null : record.key().toString())
                .param("payload", bytes).param("headers", CanonicalJson.write(headers)).param("reason", reason)
                .query(UUID.class).single());
        var entry = get(id);
        if (entry.published()) return;
        var outgoing = new ProducerRecord<String, byte[]>(properties.deadLetterTopic(), record.partition(),
                entry.key(), entry.payload());
        record.headers().forEach(header -> outgoing.headers().add(header));
        add(outgoing, "x-dead-letter-id", id.toString());
        add(outgoing, "x-original-topic", record.topic());
        add(outgoing, "x-original-partition", Integer.toString(record.partition()));
        add(outgoing, "x-original-offset", Long.toString(record.offset()));
        add(outgoing, "x-failure-reason", reason);
        send(outgoing);
        jdbc.sql("UPDATE operations.dead_letter_records SET dlq_published_at = clock_timestamp() WHERE dead_letter_id = :id")
                .param("id", id).update();
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
        if (!entry.topic().equals(properties.inputTopic())) throw new IllegalArgumentException("replay target is not the configured input topic");
        UUID token = transactions.execute(status -> {
            jdbc.sql("""
                    INSERT INTO operations.dead_letter_replays (replay_id, dead_letter_id, operator_name, reason)
                    VALUES (:replay, :id, :operator, :reason) ON CONFLICT (replay_id) DO NOTHING
                    """).param("replay", replayId).param("id", id).param("operator", operator).param("reason", reason).update();
            boolean matches = jdbc.sql("""
                    SELECT dead_letter_id = :id AND operator_name = :operator AND reason = :reason
                    FROM operations.dead_letter_replays WHERE replay_id = :replay FOR UPDATE
                    """).param("id", id).param("operator", operator).param("reason", reason)
                    .param("replay", replayId).query(Boolean.class).single();
            if (!matches) throw new IllegalArgumentException("replay request ID already belongs to a different command");
            return jdbc.sql("""
                    UPDATE operations.dead_letter_replays SET status = 'CLAIMED', lease_token = :token,
                        lease_until = clock_timestamp() + INTERVAL '2 minutes', attempt_count = attempt_count + 1
                    WHERE replay_id = :replay AND (status = 'PENDING' OR (status = 'CLAIMED' AND lease_until <= clock_timestamp()))
                    RETURNING lease_token
                    """).param("token", UUID.randomUUID()).param("replay", replayId).query(UUID.class).optional().orElse(null);
        });
        if (token == null) {
            return jdbc.sql("SELECT status = 'PUBLISHED' FROM operations.dead_letter_replays WHERE replay_id = :replay")
                    .param("replay", replayId).query(Boolean.class).single();
        }
        var outgoing = new ProducerRecord<String, byte[]>(entry.topic(), null, entry.key(), entry.payload());
        restoredHeaders(entry.headers()).forEach(header -> {
            if (!header.key().equals("x-replay-id")) outgoing.headers().add(header);
        });
        add(outgoing, "x-replay-id", replayId.toString());
        try {
            var result = send(outgoing);
            return jdbc.sql("""
                    UPDATE operations.dead_letter_replays SET status = 'PUBLISHED', published_at = clock_timestamp(),
                        broker_partition = :partition, broker_offset = :offset, lease_token = NULL, lease_until = NULL
                    WHERE replay_id = :replay AND lease_token = :token
                    """).param("partition", result.partition()).param("offset", result.offset())
                    .param("replay", replayId).param("token", token).update() == 1;
        } catch (RuntimeException failure) {
            jdbc.sql("""
                    UPDATE operations.dead_letter_replays SET status = 'PENDING', lease_token = NULL, lease_until = NULL
                    WHERE replay_id = :replay AND lease_token = :token
                    """).param("replay", replayId).param("token", token).update();
            throw failure;
        }
    }

    private Entry get(UUID id) {
        return jdbc.sql("""
                SELECT source_topic, record_key, payload, headers::text, dlq_published_at IS NOT NULL
                FROM operations.dead_letter_records WHERE dead_letter_id = :id AND consumer_name = :consumer
                """).param("id", id).param("consumer", consumer).query((rs, row) -> new Entry(rs.getString(1), rs.getString(2),
                        rs.getBytes(3), rs.getString(4), rs.getBoolean(5))).optional()
                .orElseThrow(() -> new IllegalArgumentException("unknown dead-letter entry for this consumer"));
    }

    private RecordHeaders restoredHeaders(String json) {
        var headers = new RecordHeaders();
        for (Object value : CanonicalJson.MAPPER.readValue(json, List.class)) {
            Map<?, ?> entry = (Map<?, ?>) value;
            headers.add(entry.get("name").toString(), entry.get("value") == null ? null
                    : Base64.getDecoder().decode(entry.get("value").toString()));
        }
        return headers;
    }

    private org.apache.kafka.clients.producer.RecordMetadata send(ProducerRecord<String, byte[]> record) {
        try { return kafka.send(record).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS).getRecordMetadata(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("DLQ send interrupted", failure); }
        catch (Exception failure) { throw new IllegalStateException("DLQ publication was not acknowledged", failure); }
    }

    private static void add(ProducerRecord<String, byte[]> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private record Entry(String topic, String key, byte[] payload, String headers, boolean published) { }
    public record Inspection(UUID id, String topic, int partition, long offset, String key,
                             String payloadBase64, String headers, String reason) { }
}
