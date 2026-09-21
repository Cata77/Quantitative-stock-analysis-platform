package com.quantplatform.screener.search;

import java.io.IOException;

import org.springframework.stereotype.Service;

import com.quantplatform.screener.config.ScreenerProperties;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchRequest;

@Service
public class CompanySearchService {

    private final ElasticsearchClient client;
    private final ScreenerProperties properties;

    public CompanySearchService(
            ElasticsearchClient client,
            ScreenerProperties properties
    ) {
        this.client = client;
        this.properties = properties;
    }

    public CompanySearchPage search(String rawQuery, int page, int size) {
        if(page < 0 || size < 1 || size > 100 || (long)page*size+size > 10000)
            throw new IllegalArgumentException("Search pagination exceeds the 10000 result window");
        var query = rawQuery.trim();
        try {
            var response = client.search(
                    buildRequest(query, page, size),
                    CompanySearchDocument.class);
            if(response.timedOut() || response.shards().failed().intValue()>0)
                throw new SearchUnavailableException(new IllegalStateException("Incomplete search response"));
            var content = response.hits().hits().stream()
                    .filter(hit -> hit.source() != null)
                    .map(hit -> CompanySearchItem.from(hit.source(), hit.score()))
                    .toList();
            var total = response.hits().total() == null
                    ? content.size()
                    : response.hits().total().value();
            var totalPages = (int) Math.ceil((double) total / size);
            return new CompanySearchPage(
                    query,
                    page,
                    size,
                    total,
                    totalPages,
                    content);
        } catch (ElasticsearchException exception) {
            throw new SearchUnavailableException(exception);
        } catch (IOException exception) {
            throw new SearchUnavailableException(exception);
        }
    }

    SearchRequest buildRequest(String query, int page, int size) {
        return new SearchRequest.Builder()
                .index(properties.elasticsearch().companyIndex())
                .from(page * size)
                .size(size)
                .trackTotalHits(tracking -> tracking.enabled(true))
                .sort(s -> s.score(v -> v.order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                .sort(s -> s.field(v -> v.field("instrumentId").order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)))
                .query(root -> root.multiMatch(multiMatch -> multiMatch
                        .query(query)
                        .fields(
                                "symbol^5",
                                "name^3",
                                "industry^2",
                                "sector^2",
                                "description")
                        .operator(Operator.And)
                        .fuzziness("AUTO")))
                .build();
    }
}
