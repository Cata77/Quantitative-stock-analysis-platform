package com.quantplatform.marketdata.ingestion;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.quantplatform.marketdata.event.MarketDataEventType;
import com.quantplatform.marketdata.event.StockBar;
import com.quantplatform.marketdata.kafka.MarketDataEventPublisher;
import com.quantplatform.marketdata.provider.alpaca.AlpacaStockMarketClient;
import com.quantplatform.marketdata.provider.alphavantage.AlphaVantageFundamentalClient;
import com.quantplatform.marketdata.provider.tradier.TradierOptionMarketClient;
import com.quantplatform.marketdata.support.TestProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketDataIngestionJobTest {

    @Mock
    private AlpacaStockMarketClient alpaca;

    @Mock
    private AlphaVantageFundamentalClient alphaVantage;

    @Mock
    private TradierOptionMarketClient tradier;

    @Mock
    private MarketDataEventPublisher publisher;

    @Test
    void suppressesRepeatedLatestBarsFromClosedMarkets() {
        when(alpaca.fetchLatestBar("AAPL")).thenReturn(bar());
        MarketDataIngestionJob job = job(List.of("AAPL"));

        job.collectLatestBars();
        job.collectLatestBars();

        verify(publisher, times(1)).publish(argThat(event ->
                event.eventType() == MarketDataEventType.STOCK_BAR
                        && event.symbol().equals("AAPL")));
    }

    @Test
    void oneProviderFailureDoesNotPreventOtherSymbolsFromPublishing() {
        when(alpaca.fetchLatestBar("AAPL"))
                .thenThrow(new IllegalStateException("provider unavailable"));
        when(alpaca.fetchLatestBar("MSFT")).thenReturn(bar());
        MarketDataIngestionJob job = job(List.of("AAPL", "MSFT"));

        job.collectLatestBars();

        verify(publisher).publish(argThat(event -> event.symbol().equals("MSFT")));
    }

    private MarketDataIngestionJob job(List<String> symbols) {
        return new MarketDataIngestionJob(
                TestProperties.properties(
                        true,
                        symbols,
                        true,
                        false,
                        false,
                        false),
                alpaca,
                alphaVantage,
                tradier,
                publisher,
                Clock.fixed(Instant.parse("2026-07-26T10:00:00Z"), ZoneOffset.UTC));
    }

    private StockBar bar() {
        return new StockBar(
                Instant.parse("2026-07-24T19:59:00Z"),
                new BigDecimal("213.10"),
                new BigDecimal("213.40"),
                new BigDecimal("213.00"),
                new BigDecimal("213.30"),
                12_450,
                new BigDecimal("213.22"),
                321);
    }
}
