package com.quantplatform.marketdata.kafka;

import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.event.MarketDataEvent;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaMarketDataEventPublisher implements MarketDataEventPublisher {

    private final KafkaTemplate<String, MarketDataEvent> kafkaTemplate;
    private final MarketDataProperties properties;

    public KafkaMarketDataEventPublisher(
            KafkaTemplate<String, MarketDataEvent> kafkaTemplate,
            MarketDataProperties properties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(MarketDataEvent event) {
        try {
            kafkaTemplate.send(properties.topic(), event.symbol(), event)
                    .get(properties.kafkaSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException(
                    "Kafka publishing was interrupted for " + event.symbol(),
                    exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new KafkaPublishException(
                    "Kafka publishing failed for " + event.symbol(),
                    exception);
        }
    }
}
