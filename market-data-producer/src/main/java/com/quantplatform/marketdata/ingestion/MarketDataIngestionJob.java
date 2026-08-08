package com.quantplatform.marketdata.ingestion;

import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.event.MarketDataEvent;
import com.quantplatform.marketdata.event.StockBar;
import com.quantplatform.marketdata.kafka.MarketDataEventPublisher;
import com.quantplatform.marketdata.provider.alpaca.AlpacaStockMarketClient;
import com.quantplatform.marketdata.provider.alphavantage.AlphaVantageFundamentalClient;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MarketDataIngestionJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MarketDataIngestionJob.class);

    private final MarketDataProperties properties;
    private final AlpacaStockMarketClient alpaca;
    private final AlphaVantageFundamentalClient alphaVantage;
    private final MarketDataEventPublisher publisher;
    private final Clock clock;
    private final Map<String, Instant> lastPublishedBar = new ConcurrentHashMap<>();

    public MarketDataIngestionJob(
            MarketDataProperties properties,
            AlpacaStockMarketClient alpaca,
            AlphaVantageFundamentalClient alphaVantage,
            MarketDataEventPublisher publisher,
            Clock clock
    ) {
        this.properties = properties;
        this.alpaca = alpaca;
        this.alphaVantage = alphaVantage;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${market-data.schedules.latest-bars-fixed-delay:60000}",
            initialDelayString = "${market-data.schedules.initial-delay:15000}")
    public void collectLatestBars() {
        if (!properties.enabled() || !properties.latestBarsEnabled()) {
            return;
        }
        for (String symbol : properties.symbols()) {
            collectLatestBar(symbol);
        }
    }

    @Scheduled(
            fixedDelayString = "${market-data.schedules.fundamentals-fixed-delay:86400000}",
            initialDelayString = "${market-data.schedules.initial-delay:15000}")
    public void collectFundamentals() {
        if (!properties.enabled() || !properties.fundamentalsEnabled()) {
            return;
        }
        for (String symbol : properties.symbols()) {
            try {
                var fundamentals = alphaVantage.fetchCompanyOverview(symbol);
                publisher.publish(MarketDataEvent.fundamentals(
                        symbol,
                        AlphaVantageFundamentalClient.PROVIDER,
                        clock.instant(),
                        fundamentals));
            } catch (RuntimeException exception) {
                logFailure("fundamentals", symbol, exception);
            }
        }
    }

    private void collectLatestBar(String symbol) {
        try {
            StockBar bar = alpaca.fetchLatestBar(symbol);
            Instant previous = lastPublishedBar.get(symbol);
            if (previous != null && !bar.time().isAfter(previous)) {
                return;
            }
            publisher.publish(MarketDataEvent.stockBar(
                    symbol,
                    AlpacaStockMarketClient.PROVIDER,
                    bar));
            lastPublishedBar.put(symbol, bar.time());
        } catch (RuntimeException exception) {
            logFailure("latest bar", symbol, exception);
        }
    }

    private void logFailure(String operation, String symbol, RuntimeException exception) {
        LOGGER.warn(
                "Market-data {} collection failed for {}: {}",
                operation,
                symbol,
                exception.getMessage());
    }
}
