package com.quantplatform.marketdata.provider.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.quantplatform.marketdata.config.AlpacaProperties;
import com.quantplatform.marketdata.event.StockBar;
import com.quantplatform.marketdata.support.TestProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class AlpacaStockMarketClientTest {

    @Test
    void normalizesLatestBarAndSendsAlpacaAuthentication() {
        List<ClientRequest> requests = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            requests.add(request);
            return json("""
                    {
                      "symbol":"AAPL",
                      "bar":{
                        "t":"2026-07-24T19:59:00Z",
                        "o":213.10,
                        "h":213.40,
                        "l":213.00,
                        "c":213.30,
                        "v":12450,
                        "n":321,
                        "vw":213.22
                      }
                    }
                    """);
        };

        AlpacaStockMarketClient client = client(exchange);
        StockBar bar = client.fetchLatestBar("AAPL");

        assertThat(bar.time()).isEqualTo(Instant.parse("2026-07-24T19:59:00Z"));
        assertThat(bar.close()).isEqualByComparingTo("213.30");
        assertThat(bar.volume()).isEqualTo(12_450);
        assertThat(bar.tradeCount()).isEqualTo(321);
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().headers().getFirst("APCA-API-KEY-ID"))
                .isEqualTo("test-key");
        assertThat(requests.getFirst().headers().getFirst("APCA-API-SECRET-KEY"))
                .isEqualTo("test-secret");
        assertThat(requests.getFirst().url().getQuery()).contains("feed=iex");
    }

    @Test
    void followsHistoricalPaginationInAscendingOrder() {
        List<ClientRequest> requests = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            requests.add(request);
            if (request.url().getQuery().contains("page_token=page-2")) {
                return json("""
                        {
                          "bars":[{
                            "t":"2026-07-23T04:00:00Z",
                            "o":210.0,"h":214.0,"l":209.0,"c":213.0,
                            "v":1200000,"n":45000,"vw":212.1
                          }],
                          "next_page_token":null
                        }
                        """);
            }
            return json("""
                    {
                      "bars":[{
                        "t":"2026-07-22T04:00:00Z",
                        "o":207.0,"h":211.0,"l":206.0,"c":210.0,
                        "v":1000000,"n":40000,"vw":209.2
                      }],
                      "next_page_token":"page-2"
                    }
                    """);
        };

        List<StockBar> bars = client(exchange).fetchHistoricalBars(
                "AAPL",
                Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-07-24T00:00:00Z"),
                "1Day");

        assertThat(bars).extracting(StockBar::time).containsExactly(
                Instant.parse("2026-07-22T04:00:00Z"),
                Instant.parse("2026-07-23T04:00:00Z"));
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().url().getQuery())
                .contains("timeframe=1Day", "sort=asc", "limit=10000")
                .doesNotContain("page_token");
        assertThat(requests.get(1).url().getQuery()).contains("page_token=page-2");
    }

    private AlpacaStockMarketClient client(ExchangeFunction exchange) {
        return new AlpacaStockMarketClient(
                WebClient.builder().exchangeFunction(exchange),
                new AlpacaProperties(
                        URI.create("https://data.alpaca.test"),
                        "test-key",
                        "test-secret",
                        "iex"),
                TestProperties.disabled());
    }

    private Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }
}
