package com.quantplatform.screener.search;

public record CompanySearchItem(
        String symbol,
        String name,
        String exchange,
        String country,
        String sector,
        String industry,
        String description,
        String updatedAt,
        double relevance
) {

    static CompanySearchItem from(CompanySearchDocument document, Double score) {
        return new CompanySearchItem(
                document.symbol(),
                document.name(),
                document.exchange(),
                document.country(),
                document.sector(),
                document.industry(),
                document.description(),
                document.updatedAt(),
                score == null ? 0.0 : score);
    }
}
