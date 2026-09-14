package com.quantplatform.marketdata.operations;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.ingestion.ObservationEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class OutboxRelay {
    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final DeliveryProperties properties;
    private final String worker = "relay-" + UUID.randomUUID();

    public OutboxRelay(OutboxStore store, @Qualifier("outboxKafkaTemplate") KafkaTemplate<String, String> kafka,
                       DeliveryProperties properties) {
        this.store = store;
        this.kafka = kafka;
        this.properties = properties;
    }

    public int drain() {
        int sent = 0;
        for (int i = 0; i < properties.batchSize() && !Thread.currentThread().isInterrupted(); i++) {
            var candidate = store.claim(worker);
            if (candidate.isEmpty()) break;
            var claim = candidate.get();
            try {
                if (!CanonicalJson.sha256(CanonicalJson.normalize(claim.json())).equals(claim.hash())) {
                    throw new IllegalArgumentException("stored payload hash mismatch");
                }
                ObservationEvent.parse(claim.key(), claim.json());
                var record = new ProducerRecord<>(claim.topic(), claim.key(), claim.json());
                record.headers().add(ObservationEvent.HASH_HEADER, claim.hash().getBytes(StandardCharsets.UTF_8));
                var result = kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if (store.acknowledge(claim, result.getRecordMetadata().partition(), result.getRecordMetadata().offset())) sent++;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                store.failed(claim, "INTERRUPTED");
            } catch (Exception exception) {
                store.failed(claim, exception instanceof IllegalArgumentException ? "INVALID_STORED_ENVELOPE" : "KAFKA_SEND_FAILED");
                LoggerFactory.getLogger(getClass()).warn("Outbox event {} awaits delivery recovery", claim.eventId());
            }
        }
        return sent;
    }
}
