package com.quantplatform.marketdata.provider.alpaca;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.quantplatform.marketdata.config.AlpacaProperties;
import com.quantplatform.marketdata.operations.DailyPriceProperties;
import com.quantplatform.marketdata.support.TestProperties;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

class AlpacaDailyDataClientTest {
    final ProviderRequestGate gate = mock(ProviderRequestGate.class);
    final List<ClientRequest> requests = new ArrayList<>();
    static final LocalDate DATE = LocalDate.parse("2026-09-01");
    static final LocalDate VINTAGE = LocalDate.parse("2026-09-02");

    @Test void requestsExplicitBulkDailyFeedAndFollowsOpaqueTokens() {
        var client = client("2026-09-02T12:00:00Z", request -> json("""
            {"bars":{"AAPL":[{"t":"2026-09-01T04:00:00Z","o":100,"h":110,"l":90,"c":105,"v":1000,"n":10}]},
             "next_page_token":"opaque+/="}
            """));
        var first = client.bars(List.of("AAPL","MSFT"), DATE, "RAW", null, "");
        client.bars(List.of("AAPL","MSFT"), DATE, "SPLIT_DIVIDEND", VINTAGE, first.nextPageToken());
        assertThat(first.observations()).containsKey("AAPL");
        assertThat(requests.getFirst().url().getQuery()).contains("timeframe=1Day","feed=sip","adjustment=raw",
                "asof=-","symbols=AAPL,MSFT","limit=10000");
        assertThat(requests.get(1).url().getQuery()).contains("adjustment=split,dividend","page_token=opaque+/=");
        assertThat(requests.getFirst().headers().getFirst("APCA-API-KEY-ID")).isEqualTo("test-key");
        verify(gate,times(2)).awaitTurn();
    }

    @Test void rejectsPublicationDelayAndFalseAdjustmentVintageWithoutCallingProvider() {
        var client = client("2026-09-02T04:10:00Z", request -> json("{}"));
        assertThatThrownBy(() -> client.bars(List.of("AAPL"),DATE,"RAW",null,""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("delay");
        var eligible = client("2026-09-02T12:00:00Z", request -> json("{}"));
        assertThatThrownBy(() -> eligible.bars(List.of("AAPL"),DATE,"SPLIT_DIVIDEND",DATE,""))
                .hasMessageContaining("vintage");
        assertThat(requests).isEmpty();
    }

    @Test void rejectsUnexpectedSymbolsAndInvalidPrices() {
        var client = client("2026-09-02T12:00:00Z", request -> json("""
                {"bars":{"MSFT":[]},"next_page_token":null}
                """));
        assertThatThrownBy(() -> client.bars(List.of("AAPL"),DATE,"RAW",null,""))
                .hasMessageContaining("unrequested");
        var invalid = client("2026-09-02T12:00:00Z", request -> json("""
                {"bars":{"AAPL":[{"t":"2026-09-01T04:00:00Z","o":100,"h":90,"l":95,"c":105,"v":1000,"n":10}]}}
                """));
        assertThatThrownBy(() -> invalid.bars(List.of("AAPL"),DATE,"RAW",null,""))
                .hasMessageContaining("OHLCV");
    }

    @Test void keepsActionSubjectsAndIncompleteRecords() {
        var client = client("2026-09-02T12:00:00Z", request -> json("""
                {"corporate_actions":{
                  "spin_offs":[{"id":"spin","source_symbol":"AAPL","new_symbol":"NEW","process_date":"2026-09-01"}],
                  "cash_mergers":[{"id":"merge","acquiree_symbol":"MSFT","acquirer_symbol":"AAPL","process_date":"2026-09-01"}]},
                 "next_page_token":"next"}
                """));
        var page = client.actions(List.of("AAPL","MSFT"),DATE,"");
        assertThat(page.observations().get("AAPL")).hasSize(1);
        assertThat(page.observations().get("MSFT").getFirst()).containsEntry("type","cash_mergers");
        assertThat(requests.getFirst().url().getQuery()).contains("data_quality=all","limit=1000");
    }

    @Test void propagatesQuotaDelayToSharedGate() {
        var client = client("2026-09-02T12:00:00Z", request -> Mono.just(ClientResponse
                .create(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After","120").build()));
        assertThatThrownBy(() -> client.bars(List.of("AAPL"),DATE,"RAW",null,""))
                .isInstanceOf(ProviderRequestGate.DeferredRequest.class);
        verify(gate).postpone(Duration.ofSeconds(120));
    }

    private AlpacaDailyDataClient client(String now, ExchangeFunction response) {
        return new AlpacaDailyDataClient(WebClient.builder().exchangeFunction(request -> {
            requests.add(request); return response.exchange(request);
        }), new AlpacaProperties(URI.create("https://data.alpaca.test"),"test-key","test-secret","iex"),
            TestProperties.disabled(),new DailyPriceProperties("sip",100,10000,Duration.ofMillis(350),
                Duration.ofMinutes(16),true,true,14),gate,Clock.fixed(Instant.parse(now),ZoneOffset.UTC));
    }
    private Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(body).build());
    }
}
