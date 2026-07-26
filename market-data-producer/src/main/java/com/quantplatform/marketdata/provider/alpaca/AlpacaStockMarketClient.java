package com.quantplatform.marketdata.provider.alpaca;

import com.quantplatform.marketdata.config.AlpacaProperties;
import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.event.StockBar;
import com.quantplatform.marketdata.provider.MarketDataProviderException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

@Component
public class AlpacaStockMarketClient {

    public static final String PROVIDER = "alpaca";

    private final WebClient webClient;
    private final AlpacaProperties alpaca;
    private final MarketDataProperties marketData;

    public AlpacaStockMarketClient(
            WebClient.Builder webClientBuilder,
            AlpacaProperties alpaca,
            MarketDataProperties marketData
    ) {
        WebClient.Builder alpacaClient = webClientBuilder.clone()
                .baseUrl(alpaca.baseUrl().toString());
        if (alpaca.keyId() != null && !alpaca.keyId().isBlank()) {
            alpacaClient.defaultHeader("APCA-API-KEY-ID", alpaca.keyId());
        }
        if (alpaca.secretKey() != null && !alpaca.secretKey().isBlank()) {
            alpacaClient.defaultHeader("APCA-API-SECRET-KEY", alpaca.secretKey());
        }
        this.webClient = alpacaClient.build();
        this.alpaca = alpaca;
        this.marketData = marketData;
    }

    public StockBar fetchLatestBar(String symbol) {
        try {
            AlpacaLatestBarResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/stocks/{symbol}/bars/latest")
                            .queryParam("feed", alpaca.feed())
                            .queryParam("currency", "USD")
                            .build(symbol))
                    .retrieve()
                    .bodyToMono(AlpacaLatestBarResponse.class)
                    .block(marketData.providerTimeout());
            if (response == null || response.bar() == null) {
                throw new MarketDataProviderException(
                        "Alpaca returned no latest bar for " + symbol);
            }
            return response.bar().toStockBar();
        } catch (WebClientException exception) {
            throw providerFailure("latest bar", symbol, exception);
        }
    }

    public List<StockBar> fetchHistoricalBars(
            String symbol,
            Instant start,
            Instant end,
            String timeframe
    ) {
        List<StockBar> result = new ArrayList<>();
        Set<String> seenPageTokens = new HashSet<>();
        String pageToken = null;
        do {
            AlpacaHistoricalBarsResponse response =
                    fetchHistoricalPage(symbol, start, end, timeframe, pageToken);
            if (response.bars() != null) {
                response.bars().stream().map(AlpacaBar::toStockBar).forEach(result::add);
            }
            pageToken = response.next_page_token();
            if (pageToken != null && !pageToken.isBlank() && !seenPageTokens.add(pageToken)) {
                throw new MarketDataProviderException(
                        "Alpaca repeated a historical pagination token for " + symbol);
            }
        } while (pageToken != null && !pageToken.isBlank());
        return List.copyOf(result);
    }

    private AlpacaHistoricalBarsResponse fetchHistoricalPage(
            String symbol,
            Instant start,
            Instant end,
            String timeframe,
            String pageToken
    ) {
        try {
            AlpacaHistoricalBarsResponse response = webClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder
                                .path("/v2/stocks/{symbol}/bars")
                                .queryParam("timeframe", timeframe)
                                .queryParam("start", start)
                                .queryParam("end", end)
                                .queryParam("limit", 10_000)
                                .queryParam("adjustment", "raw")
                                .queryParam("feed", alpaca.feed())
                                .queryParam("currency", "USD")
                                .queryParam("sort", "asc");
                        if (pageToken != null && !pageToken.isBlank()) {
                            builder.queryParam("page_token", pageToken);
                        }
                        return builder.build(symbol);
                    })
                    .retrieve()
                    .bodyToMono(AlpacaHistoricalBarsResponse.class)
                    .block(marketData.providerTimeout());
            if (response == null) {
                throw new MarketDataProviderException(
                        "Alpaca returned no historical response for " + symbol);
            }
            return response;
        } catch (WebClientException exception) {
            throw providerFailure("historical bars", symbol, exception);
        }
    }

    private MarketDataProviderException providerFailure(
            String operation,
            String symbol,
            WebClientException cause
    ) {
        return new MarketDataProviderException(
                "Alpaca " + operation + " request failed for " + symbol, cause);
    }

    private record AlpacaLatestBarResponse(String symbol, AlpacaBar bar) {
    }

    private record AlpacaHistoricalBarsResponse(
            List<AlpacaBar> bars,
            String next_page_token
    ) {
    }

    private record AlpacaBar(
            Instant t,
            BigDecimal o,
            BigDecimal h,
            BigDecimal l,
            BigDecimal c,
            long v,
            long n,
            BigDecimal vw
    ) {

        StockBar toStockBar() {
            return new StockBar(t, o, h, l, c, v, vw, n);
        }
    }
}
