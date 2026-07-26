package com.quantplatform.scoring.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class MarketDataEventParserTest {

    private MarketDataEventParser parser;

    @BeforeEach
    void setUp() {
        parser = new MarketDataEventParser(
                JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void parsesAValidProducerEvent() {
        var event = parser.parse("AAPL", validBarJson("123.45"));

        assertThat(event.symbol()).isEqualTo("AAPL");
        assertThat(event.stockBar().close()).isEqualByComparingTo("123.45");
    }

    @Test
    void rejectsMalformedJsonForDeadLetterRecovery() {
        assertThatThrownBy(() -> parser.parse("AAPL", "{not-json"))
                .isInstanceOf(MarketDataValidationException.class)
                .hasMessage("invalid market data event");
    }

    @Test
    void rejectsANegativePriceForDeadLetterRecovery() {
        assertThatThrownBy(() -> parser.parse("AAPL", validBarJson("-1")))
                .isInstanceOf(MarketDataValidationException.class)
                .hasMessage("invalid market data event");
    }

    @Test
    void rejectsARecordWhoseKeyDoesNotMatchItsSymbol() {
        assertThatThrownBy(() -> parser.parse("MSFT", validBarJson("123.45")))
                .isInstanceOf(MarketDataValidationException.class)
                .hasMessageContaining("record key");
    }

    private String validBarJson(String close) {
        return """
                {
                  "eventId": "158610ca-7952-455b-9d95-c6b678f189c6",
                  "schemaVersion": 1,
                  "eventType": "STOCK_BAR",
                  "symbol": "AAPL",
                  "provider": "alpaca",
                  "observedAt": "2026-07-25T20:00:00Z",
                  "stockBar": {
                    "time": "2026-07-25T20:00:00Z",
                    "open": 120,
                    "high": 130,
                    "low": 110,
                    "close": %s,
                    "volume": 1000,
                    "volumeWeightedAveragePrice": 122,
                    "tradeCount": 200
                  },
                  "fundamentals": null,
                  "option": null
                }
                """.formatted(close);
    }
}
