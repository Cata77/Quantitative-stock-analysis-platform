package com.quantplatform.screener.search;

public record CompanySearchDocument(
        String symbol,
        String name,
        String exchange,
        String country,
        String sector,
        String industry,
        String description,
        String updatedAt
) {
}
