package com.quantplatform.marketdata.support;

import com.quantplatform.marketdata.config.MarketDataProperties;
import java.time.Duration;
import java.util.List;

public final class TestProperties {

    private TestProperties() {
    }

    public static MarketDataProperties disabled() {
        return properties(false, List.of("AAPL"), true, false, false);
    }

    public static MarketDataProperties properties(
            boolean enabled,
            List<String> symbols,
            boolean latestBars,
            boolean backfill,
            boolean fundamentals
    ) {
        return new MarketDataProperties(
                enabled,
                symbols,
                "market-data-events",
                6,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                latestBars,
                backfill,
                365,
                "1Day",
                fundamentals);
    }
}
