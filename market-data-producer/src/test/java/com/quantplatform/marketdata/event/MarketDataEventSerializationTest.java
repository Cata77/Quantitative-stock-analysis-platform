package com.quantplatform.marketdata.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

class MarketDataEventSerializationTest {

    @Test
    void serializesStableVersionedJsonWithoutJavaTypeMetadata() {
        MarketDataEvent event = MarketDataEvent.stockBar(
                "aapl",
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

        byte[] bytes;
        try (JacksonJsonSerializer<MarketDataEvent> serializer =
                     new JacksonJsonSerializer<>()) {
            serializer.setAddTypeInfo(false);
            bytes = serializer.serialize("market-data-events", event);
        }
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertThat(json)
                .contains("\"schemaVersion\":1")
                .contains("\"eventType\":\"STOCK_BAR\"")
                .contains("\"symbol\":\"AAPL\"")
                .contains("\"close\":213.30")
                .doesNotContain("__TypeId__");
    }
}
