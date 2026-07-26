package com.quantplatform.marketdata.provider.tradier;

import static org.assertj.core.api.Assertions.assertThat;

import com.quantplatform.marketdata.config.TradierProperties;
import com.quantplatform.marketdata.event.OptionType;
import com.quantplatform.marketdata.support.TestProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

class TradierOptionMarketClientTest {

    @Test
    void selectsNearestExpirationAndNormalizesCompleteOptionSnapshots() {
        List<ClientRequest> requests = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            requests.add(request);
            if (request.url().getPath().endsWith("/markets/options/expirations")) {
                return json("""
                        {"expirations":{"date":["2026-08-21","2026-09-18"]}}
                        """);
            }
            return json("""
                    {
                      "options":{
                        "option":[{
                          "symbol":"AAPL260821C00220000",
                          "last":8.45,
                          "volume":1200,
                          "bid":8.40,
                          "ask":8.50,
                          "underlying":"AAPL",
                          "strike":220.0,
                          "open_interest":4500,
                          "expiration_date":"2026-08-21",
                          "option_type":"call",
                          "trade_date":1785000000000,
                          "greeks":{
                            "delta":0.56,
                            "gamma":0.031,
                            "theta":-0.12,
                            "vega":0.24,
                            "rho":0.08,
                            "mid_iv":0.31,
                            "smv_vol":0.305
                          }
                        }]
                      }
                    }
                    """);
        };
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-26T10:00:00Z"),
                ZoneOffset.UTC);
        TradierOptionMarketClient client = new TradierOptionMarketClient(
                WebClient.builder().exchangeFunction(exchange),
                new TradierProperties(
                        URI.create("https://sandbox.tradier.test/v1"),
                        "tradier-test-token"),
                TestProperties.disabled(),
                clock);

        var snapshots = client.fetchNearestOptionChain("AAPL");

        assertThat(snapshots).hasSize(1);
        var snapshot = snapshots.getFirst();
        assertThat(snapshot.contractSymbol()).isEqualTo("AAPL260821C00220000");
        assertThat(snapshot.optionType()).isEqualTo(OptionType.CALL);
        assertThat(snapshot.openInterest()).isEqualTo(4_500);
        assertThat(snapshot.impliedVolatility()).isEqualByComparingTo("0.305");
        assertThat(snapshot.delta()).isEqualByComparingTo("0.56");
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer tradier-test-token");
        assertThat(requests.get(1).url().getQuery())
                .contains("expiration=2026-08-21", "greeks=true");
    }

    @Test
    void omitsContractsWhoseGreeksAreIncomplete() {
        ExchangeFunction exchange = request -> {
            if (request.url().getPath().endsWith("/markets/options/expirations")) {
                return json("""
                        {"expirations":{"date":"2026-08-21"}}
                        """);
            }
            return json("""
                    {
                      "options":{"option":{
                        "symbol":"AAPL260821P00200000",
                        "last":2.1,
                        "bid":2.0,
                        "ask":2.2,
                        "strike":200,
                        "expiration_date":"2026-08-21",
                        "option_type":"put",
                        "greeks":{"delta":-0.2}
                      }}
                    }
                    """);
        };
        TradierOptionMarketClient client = new TradierOptionMarketClient(
                WebClient.builder().exchangeFunction(exchange),
                new TradierProperties(
                        URI.create("https://sandbox.tradier.test/v1"),
                        "token"),
                TestProperties.disabled(),
                Clock.fixed(Instant.parse("2026-07-26T10:00:00Z"), ZoneOffset.UTC));

        assertThat(client.fetchNearestOptionChain("AAPL")).isEmpty();
    }

    private Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }
}
