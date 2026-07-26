package com.quantplatform.marketdata.provider.alphavantage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quantplatform.marketdata.config.AlphaVantageProperties;
import com.quantplatform.marketdata.provider.MarketDataProviderException;
import com.quantplatform.marketdata.support.TestProperties;
import java.net.URI;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class AlphaVantageFundamentalClientTest {

    @Test
    void normalizesCompanyOverviewMetrics() {
        AtomicReference<ClientRequest> requestReference = new AtomicReference<>();
        AlphaVantageFundamentalClient client = client(request -> {
            requestReference.set(request);
            return json("""
                    {
                      "Symbol":"AAPL",
                      "AssetType":"Common Stock",
                      "Name":"Apple Inc",
                      "Exchange":"NASDAQ",
                      "Currency":"USD",
                      "Country":"USA",
                      "Sector":"TECHNOLOGY",
                      "Industry":"ELECTRONIC COMPUTERS",
                      "LatestQuarter":"2026-06-30",
                      "MarketCapitalization":"3200000000000",
                      "RevenueTTM":"410000000000",
                      "EBITDA":"145000000000",
                      "PERatio":"31.4",
                      "PEGRatio":"2.1",
                      "PriceToBookRatio":"45.8",
                      "EPS":"7.02",
                      "ProfitMargin":"0.248",
                      "OperatingMarginTTM":"0.318",
                      "ReturnOnAssetsTTM":"0.231",
                      "ReturnOnEquityTTM":"1.52",
                      "QuarterlyRevenueGrowthYOY":"0.051",
                      "QuarterlyEarningsGrowthYOY":"0.092",
                      "AnalystTargetPrice":"228.00",
                      "Beta":"1.21"
                    }
                    """);
        });

        var snapshot = client.fetchCompanyOverview("AAPL");

        assertThat(snapshot.name()).isEqualTo("Apple Inc");
        assertThat(snapshot.latestQuarter()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(snapshot.marketCapitalization())
                .isEqualByComparingTo("3200000000000");
        assertThat(snapshot.returnOnEquityTtm()).isEqualByComparingTo("1.52");
        assertThat(requestReference.get().url().getQuery())
                .contains("function=OVERVIEW", "symbol=AAPL", "apikey=test-alpha-key");
    }

    @Test
    void surfacesProviderRateLimitMessages() {
        AlphaVantageFundamentalClient client = client(request -> json("""
                {"Note":"Free API call frequency has been exceeded"}
                """));

        assertThatThrownBy(() -> client.fetchCompanyOverview("AAPL"))
                .isInstanceOf(MarketDataProviderException.class)
                .hasMessageContaining("Free API call frequency has been exceeded");
    }

    private AlphaVantageFundamentalClient client(ExchangeFunction exchange) {
        return new AlphaVantageFundamentalClient(
                WebClient.builder().exchangeFunction(exchange),
                new AlphaVantageProperties(
                        URI.create("https://alphavantage.test"),
                        "test-alpha-key"),
                TestProperties.disabled());
    }

    private Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }
}
