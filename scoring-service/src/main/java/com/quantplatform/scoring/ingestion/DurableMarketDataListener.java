package com.quantplatform.scoring.ingestion;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.ingestion.ObservationEvent;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DurableMarketDataListener {
    private final DurableObservationProcessor processor;

    public DurableMarketDataListener(DurableObservationProcessor processor) { this.processor = processor; }

    @KafkaListener(id = "durable-observations", topics = "${scoring.input-topic}",
            groupId = "${spring.kafka.consumer.group-id}", concurrency = "${scoring.consumer-concurrency}")
    public void consume(ConsumerRecord<String, byte[]> record) {
        final ObservationEvent event;
        try {
            if (record.value() == null) throw new IllegalArgumentException("empty observation");
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(record.value())).toString();
            event = ObservationEvent.parse(record.key(), json);
            var hash = record.headers().lastHeader(ObservationEvent.HASH_HEADER);
            if (hash == null || hash.value() == null
                    || !event.payloadHash().equals(new String(hash.value(), StandardCharsets.UTF_8))
                    || !event.payloadHash().equals(CanonicalJson.sha256(CanonicalJson.normalize(json)))) {
                throw new IllegalArgumentException("payload hash is missing or invalid");
            }
        } catch (Exception exception) {
            throw new MarketDataValidationException("invalid observation envelope", exception);
        }
        // TransactionTemplate returns only after commit. RECORD acknowledgement follows listener return.
        processor.process(event, record.topic(), record.partition(), record.offset());
    }
}
