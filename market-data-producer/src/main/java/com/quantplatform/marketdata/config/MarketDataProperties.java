package com.quantplatform.marketdata.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("market-data")
public record MarketDataProperties(
        boolean enabled,
        List<String> symbols,
        String topic,
        int topicPartitions,
        Duration providerTimeout,
        Duration kafkaSendTimeout,
        boolean latestBarsEnabled,
        boolean historicalBackfillEnabled,
        int historicalLookbackDays,
        String historicalTimeframe,
        boolean fundamentalsEnabled
) {

    public MarketDataProperties {
        symbols = symbols == null
                ? List.of()
                : symbols.stream()
                        .map(symbol -> symbol.trim().toUpperCase(Locale.ROOT))
                        .filter(symbol -> !symbol.isEmpty())
                        .distinct()
                        .toList();
        if (symbols.stream().anyMatch(symbol -> !symbol.matches("[A-Z0-9./-]{1,10}"))) {
            throw new IllegalArgumentException(
                    "market-data.symbols must contain valid tickers of at most 10 characters");
        }
        if (enabled && symbols.isEmpty()) {
            throw new IllegalArgumentException(
                    "market-data.symbols must not be empty when collection is enabled");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("market-data.topic must not be blank");
        }
        if (topicPartitions < 1) {
            throw new IllegalArgumentException("market-data.topic-partitions must be positive");
        }
        if (providerTimeout == null || providerTimeout.isZero() || providerTimeout.isNegative()) {
            throw new IllegalArgumentException("market-data.provider-timeout must be positive");
        }
        if (kafkaSendTimeout == null
                || kafkaSendTimeout.isZero()
                || kafkaSendTimeout.isNegative()) {
            throw new IllegalArgumentException("market-data.kafka-send-timeout must be positive");
        }
        if (historicalLookbackDays < 1) {
            throw new IllegalArgumentException(
                    "market-data.historical-lookback-days must be positive");
        }
        if (historicalTimeframe == null
                || !historicalTimeframe.matches(
                        "([1-9]|[1-5][0-9])(Min|T)|([1-9]|1[0-9]|2[0-3])(Hour|H)"
                                + "|1(Day|D|Week|W)|(1|2|3|4|6|12)(Month|M)")) {
            throw new IllegalArgumentException(
                    "market-data.historical-timeframe is not supported by Alpaca");
        }
    }
}
