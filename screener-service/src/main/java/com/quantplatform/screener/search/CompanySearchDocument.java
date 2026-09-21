package com.quantplatform.screener.search;
import java.util.Map;
public record CompanySearchDocument(String instrumentId, String symbol, String name, String exchange,
        String country, String sector, String industry, String description, String updatedAt,
        Map<String,Object> score, int schemaVersion) {}
