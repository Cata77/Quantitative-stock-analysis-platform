package com.quantplatform.screener.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.quantplatform.screener.config.ScreenerProperties;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;

@SuppressWarnings("unchecked")
class CompanySearchServiceTest {

    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final CompanySearchService service = new CompanySearchService(
            client,
            new ScreenerProperties(
                    new ScreenerProperties.Elasticsearch(
                            "http://localhost:9200",
                            "companies")));

    @Test
    void buildsAWeightedFuzzyMultiFieldRequest() {
        var request = service.buildRequest("semiconductor", 2, 20);

        assertThat(request.index()).containsExactly("companies");
        assertThat(request.from()).isEqualTo(40);
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.trackTotalHits().enabled()).isTrue();
        assertThat(request.query().multiMatch().query()).isEqualTo("semiconductor");
        assertThat(request.query().multiMatch().fields())
                .containsExactly(
                        "symbol^5",
                        "name^3",
                        "industry^2",
                        "sector^2",
                        "description");
        assertThat(request.query().multiMatch().fuzziness()).isEqualTo("AUTO");
    }

    @Test
    void mapsSearchHitsAndTrimsTheQuery() throws Exception {
        var document = new CompanySearchDocument(
                "NVDA",
                "NVIDIA Corporation",
                "NASDAQ",
                "USA",
                "Technology",
                "Semiconductors",
                "Accelerated computing and graphics",
                "2026-07-25T20:00:00Z");
        var hit = Hit.<CompanySearchDocument>of(builder -> builder
                .index("companies")
                .id("NVDA")
                .score(8.25)
                .source(document));
        var response = response(hit);
        when(client.search(any(SearchRequest.class), eq(CompanySearchDocument.class)))
                .thenReturn(response);

        var page = service.search("  nvidia  ", 0, 20);

        assertThat(page.query()).isEqualTo("nvidia");
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).singleElement().satisfies(result -> {
            assertThat(result.symbol()).isEqualTo("NVDA");
            assertThat(result.relevance()).isEqualTo(8.25);
        });
    }

    @Test
    void translatesTransportFailuresToServiceUnavailable() throws Exception {
        when(client.search(any(SearchRequest.class), eq(CompanySearchDocument.class)))
                .thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> service.search("nvidia", 0, 20))
                .isInstanceOf(SearchUnavailableException.class)
                .hasMessage("Company search is temporarily unavailable");
    }

    private SearchResponse<CompanySearchDocument> response(
            Hit<CompanySearchDocument> hit
    ) {
        var total = TotalHits.of(builder -> builder
                .value(1)
                .relation(TotalHitsRelation.Eq));
        var hits = HitsMetadata.<CompanySearchDocument>of(builder -> builder
                .total(total)
                .hits(hit));
        var shards = ShardStatistics.of(builder -> builder
                .total(1)
                .successful(1)
                .failed(0));
        return SearchResponse.of(builder -> builder
                .took(1)
                .timedOut(false)
                .shards(shards)
                .hits(hits));
    }
}
