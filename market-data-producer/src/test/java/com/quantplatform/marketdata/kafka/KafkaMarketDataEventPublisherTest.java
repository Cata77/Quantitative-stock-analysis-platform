package com.quantplatform.marketdata.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.quantplatform.marketdata.event.MarketDataEvent;
import com.quantplatform.marketdata.event.StockBar;
import com.quantplatform.marketdata.support.TestProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class KafkaMarketDataEventPublisherTest {

    @Mock
    private KafkaTemplate<String, MarketDataEvent> kafkaTemplate;

    @Test
    void alwaysPublishesWithTheStockSymbolAsKafkaKey() {
        MarketDataEvent event = event();
        when(kafkaTemplate.send("market-data-events", "AAPL", event))
                .thenReturn(CompletableFuture.completedFuture(null));
        KafkaMarketDataEventPublisher publisher =
                new KafkaMarketDataEventPublisher(kafkaTemplate, TestProperties.disabled());

        publisher.publish(event);

        verify(kafkaTemplate).send("market-data-events", "AAPL", event);
    }

    @Test
    void propagatesBrokerFailures() {
        MarketDataEvent event = event();
        CompletableFuture<org.springframework.kafka.support.SendResult<String, MarketDataEvent>>
                failure = new CompletableFuture<>();
        failure.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send("market-data-events", "AAPL", event)).thenReturn(failure);
        KafkaMarketDataEventPublisher publisher =
                new KafkaMarketDataEventPublisher(kafkaTemplate, TestProperties.disabled());

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(KafkaPublishException.class)
                .hasMessageContaining("AAPL");
    }

    private MarketDataEvent event() {
        return MarketDataEvent.stockBar(
                "AAPL",
                "alpaca",
                new StockBar(
                        Instant.parse("2026-07-24T19:59:00Z"),
                        new BigDecimal("213.10"),
                        new BigDecimal("213.40"),
                        new BigDecimal("213.00"),
                        new BigDecimal("213.30"),
                        12_450,
                        new BigDecimal("213.22"),
                        321));
    }
}
