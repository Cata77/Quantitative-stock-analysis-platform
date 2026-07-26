package com.quantplatform.scoring.ingestion.event;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record MarketDataEvent(
        UUID eventId,
        int schemaVersion,
        MarketDataEventType eventType,
        String symbol,
        String provider,
        Instant observedAt,
        StockBar stockBar,
        FundamentalSnapshot fundamentals,
        OptionSnapshot option
) {

    private static final int CURRENT_SCHEMA_VERSION = 1;

    public MarketDataEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        symbol = Objects.requireNonNull(symbol, "symbol must not be null")
                .toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9./-]{1,10}")) {
            throw new IllegalArgumentException("symbol is invalid");
        }
        if (Objects.requireNonNull(provider, "provider must not be null").isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion is unsupported");
        }
        int payloads = (stockBar == null ? 0 : 1)
                + (fundamentals == null ? 0 : 1)
                + (option == null ? 0 : 1);
        if (payloads != 1) {
            throw new IllegalArgumentException("exactly one event payload is required");
        }
        if ((eventType == MarketDataEventType.STOCK_BAR) != (stockBar != null)
                || (eventType == MarketDataEventType.FUNDAMENTAL_SNAPSHOT)
                        != (fundamentals != null)
                || (eventType == MarketDataEventType.OPTION_SNAPSHOT) != (option != null)) {
            throw new IllegalArgumentException("eventType does not match its payload");
        }
    }
}
