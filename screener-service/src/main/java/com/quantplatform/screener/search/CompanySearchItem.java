package com.quantplatform.screener.search;
import java.util.Map;
/** One search result; {@code relevance} is the PostgreSQL match score (higher is better). */
public record CompanySearchItem(String instrumentId, String symbol, String name, String exchange,
        String country, String sector, String industry, String description, String updatedAt,
        Map<String,Object> score, int schemaVersion, double relevance) {}
