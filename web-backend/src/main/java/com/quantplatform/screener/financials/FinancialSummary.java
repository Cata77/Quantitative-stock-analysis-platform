package com.quantplatform.screener.financials;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Compact, user-facing view of one instrument in one publication (view=summary).
 * Null fields are omitted; a missing value carries only its reason.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinancialSummary(
        Company company,
        Map<String, Figure> valuation,
        Map<String, Figure> profitability,
        Map<String, Figure> financials,
        Map<String, Figure> market,
        ScoreSummary score,
        String definitionsVersion,
        String note) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Company(UUID instrumentId, String symbol, String sector, String profile, UUID runId,
            LocalDate asOfDate, LocalDate financialPeriodEnd, LocalDate filingDate) {}

    /** unit: USD, shares, MULTIPLE (x), PERCENT or INDEX; period describes what the value covers. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Figure(BigDecimal value, String unit, String period, String reason) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScoreSummary(boolean eligible, String status, Integer rank, Integer rankedInstruments,
            BigDecimal percentile, BigDecimal compositeScore, BigDecimal valueScore, BigDecimal qualityScore,
            BigDecimal momentumScore, List<String> exclusionReasons, Map<String, String> missingMetrics) {}
}
