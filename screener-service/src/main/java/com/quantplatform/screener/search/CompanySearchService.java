package com.quantplatform.screener.search;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Company search on PostgreSQL; the former Elasticsearch index and rebuild job were removed (P2s). */
@Service
public class CompanySearchService {
    static final int MAX_WINDOW = 10000;
    private final CompanySearchRepository repository;
    private final Clock clock;

    public CompanySearchService(CompanySearchRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CompanySearchPage search(String rawQuery, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size + size > MAX_WINDOW)
            throw new IllegalArgumentException("Search pagination exceeds the 10000 result window");
        var query = rawQuery.trim();
        var tokens = tokens(query);
        if (tokens.isEmpty()) return CompanySearchPage.empty(query, page, size);
        var result = repository.search(tokens, page, size, clock.instant());
        long total = ((Number) result.get("total")).longValue();
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) result.get("content");
        var content = rows.stream().map(CompanySearchService::item).toList();
        return new CompanySearchPage(query, page, size, total, (int) Math.ceil((double) total / size), content);
    }

    static List<String> tokens(String query) {
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+")).filter(t -> !t.isEmpty()).distinct().toList();
    }

    @SuppressWarnings("unchecked")
    private static CompanySearchItem item(Map<String, Object> row) {
        var d = (Map<String, Object>) row.get("document");
        return new CompanySearchItem(str(d.get("instrumentId")), str(d.get("symbol")), str(d.get("name")),
                str(d.get("exchange")), str(d.get("country")), str(d.get("sector")), str(d.get("industry")),
                str(d.get("description")), str(d.get("updatedAt")), (Map<String, Object>) d.get("score"),
                ((Number) d.get("schemaVersion")).intValue(), ((Number) row.get("relevance")).doubleValue());
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
