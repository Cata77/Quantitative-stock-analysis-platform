package com.quantplatform.scoring.ingestion;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class MarketDataEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketDataEventListener.class);

    private final MarketDataEventParser parser;
    private final MarketDataEventProcessor processor;

    public MarketDataEventListener(
            MarketDataEventParser parser,
            MarketDataEventProcessor processor
    ) {
        this.parser = parser;
        this.processor = processor;
    }

    @KafkaListener(
            topics = "${scoring.input-topic}",
            concurrency = "${scoring.consumer-concurrency}")
    public void consume(ConsumerRecord<String, String> record) {
        var event = parser.parse(record.key(), record.value());
        processor.process(event);
        LOGGER.debug(
                "Processed {} event {} for {} from partition {} at offset {}",
                event.eventType(),
                event.eventId(),
                event.symbol(),
                record.partition(),
                record.offset());
    }
}
