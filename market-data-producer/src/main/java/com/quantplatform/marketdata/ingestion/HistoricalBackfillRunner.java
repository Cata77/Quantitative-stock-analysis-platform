package com.quantplatform.marketdata.ingestion;

import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.event.MarketDataEvent;
import com.quantplatform.marketdata.kafka.MarketDataEventPublisher;
import com.quantplatform.marketdata.provider.alpaca.AlpacaStockMarketClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class HistoricalBackfillRunner implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HistoricalBackfillRunner.class);

    private final MarketDataProperties properties;
    private final AlpacaStockMarketClient alpaca;
    private final MarketDataEventPublisher publisher;
    private final Clock clock;

    public HistoricalBackfillRunner(
            MarketDataProperties properties,
            AlpacaStockMarketClient alpaca,
            MarketDataEventPublisher publisher,
            Clock clock
    ) {
        this.properties = properties;
        this.alpaca = alpaca;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.enabled() || !properties.historicalBackfillEnabled()) {
            return;
        }

        Instant end = clock.instant().minus(Duration.ofMinutes(15));
        Instant start = end.minus(Duration.ofDays(properties.historicalLookbackDays()));
        for (String symbol : properties.symbols()) {
            try {
                var bars = alpaca.fetchHistoricalBars(
                        symbol,
                        start,
                        end,
                        properties.historicalTimeframe());
                for (var bar : bars) {
                    publisher.publish(MarketDataEvent.stockBar(
                            symbol,
                            AlpacaStockMarketClient.PROVIDER,
                            bar));
                }
                LOGGER.info("Published {} historical bars for {}", bars.size(), symbol);
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Historical market-data backfill failed for {}: {}",
                        symbol,
                        exception.getMessage());
            }
        }
    }
}
