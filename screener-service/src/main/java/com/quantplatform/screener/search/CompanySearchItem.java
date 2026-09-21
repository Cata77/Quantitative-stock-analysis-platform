package com.quantplatform.screener.search;
import java.util.Map;
public record CompanySearchItem(String instrumentId, String symbol, String name, String exchange,
        String country, String sector, String industry, String description, String updatedAt,
        Map<String,Object> score, int schemaVersion, double relevance) {
    static CompanySearchItem from(CompanySearchDocument d,Double score) {
        return new CompanySearchItem(d.instrumentId(),d.symbol(),d.name(),d.exchange(),d.country(),
                d.sector(),d.industry(),d.description(),d.updatedAt(),d.score(),d.schemaVersion(),
                score==null?0:score);
    }
}
