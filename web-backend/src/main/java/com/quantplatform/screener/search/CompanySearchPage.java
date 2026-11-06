package com.quantplatform.screener.search;

import java.util.List;

public record CompanySearchPage(
        String query,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<CompanySearchItem> content
) {

    static CompanySearchPage empty(String query, int page, int size) {
        return new CompanySearchPage(query, page, size, 0, 0, List.of());
    }
}
