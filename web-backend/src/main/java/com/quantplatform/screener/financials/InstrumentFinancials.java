package com.quantplatform.screener.financials;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Financial data and indicators of one instrument, read from a single published run.
 * Reported inputs, model derivations, normalized metrics and the informational RSI are
 * kept in separate sections; nothing here is recalculated from current data.
 */
public record InstrumentFinancials(
        Publication run,
        Instrument instrument,
        Score score,
        List<InputValue> inputs,
        List<DerivedValue> derived,
        List<MetricValue> metrics,
        Technical technical,
        Supplemental supplemental) {

    public record Publication(UUID id, LocalDate asOfDate, OffsetDateTime marketCutoff,
            OffsetDateTime knowledgeCutoff, OffsetDateTime publishedAt, String modelVersion,
            String preprocessingVersion, String mappingVersion, String timingPolicy, Integer scoredCount) {}

    public record Instrument(UUID instrumentId, UUID issuerId, String symbol, String profile,
            String sector, String peerGroup, Boolean inSp500, Boolean inNasdaq100,
            LocalDate financialPeriodEnd, LocalDate filingDate) {}

    public record Score(boolean eligible, String scoreStatus, Integer rank, BigDecimal percentile,
            BigDecimal compositeScore, BigDecimal valueScore, BigDecimal qualityScore,
            BigDecimal momentumScore, BigDecimal valueContribution, BigDecimal qualityContribution,
            BigDecimal momentumContribution, Object coverage, Object effectiveWeights,
            List<String> warnings, List<Exclusion> exclusions) {}

    public record Exclusion(String stage, String reasonCode, String affectedMetric, String detail) {}

    /** A prepared model input: a reported fact, a TTM/derived accounting value or a market value. */
    public record InputValue(String code, String category, String periodType, String valueKind,
            BigDecimal value, String unit, LocalDate periodEnd, LocalDate filedDate,
            OffsetDateTime availableAt, OffsetDateTime observedAt, String availabilityBasis,
            String derivation, List<String> sourceIds, String status, String reason) {}

    /** An intermediate value computed by the scoring model and stored with the publication. */
    public record DerivedValue(String code, BigDecimal value, String unit, String formula,
            String status, String reason) {}

    /** A model metric: raw ratio before normalization and its normalized contribution. */
    public record MetricValue(String metric, String family, String unit, String formula,
            BigDecimal rawValue, String reason, BigDecimal transformedValue,
            BigDecimal winsorizedValue, String cohort, Integer cohortCount, BigDecimal lowerBound,
            BigDecimal upperBound, BigDecimal cohortMean, BigDecimal cohortDeviation,
            BigDecimal zScore, BigDecimal clippedScore, BigDecimal weight, BigDecimal contribution) {}

    public record Technical(Rsi rsi) {}

    public record Rsi(BigDecimal value, int period, String method, String version, String priceBasis,
            UUID adjustedDataset, LocalDate adjustmentBasis, int maximumSessions, int sessionsUsed,
            LocalDate firstSession, LocalDate lastSession, boolean partOfScore, String status,
            String reason) {}

    /** Informational values read from the publication's retained sources; never model inputs. */
    public record Supplemental(DepreciationAmortization depreciationAmortization) {}

    /** D&amp;A over the periods of OPERATING_INCOME_TTM and the resulting EBITDA (EBITDA_FROM_SOURCE_V1). */
    public record DepreciationAmortization(BigDecimal value, BigDecimal ebitda, LocalDate periodEnd, String concept,
            String version, String method, List<DepreciationComponent> components, boolean partOfScore,
            String status, String reason) {}

    /** One reported period: sign in the TTM combination, D&amp;A and the operating income it pairs with. */
    public record DepreciationComponent(LocalDate periodStart, LocalDate periodEnd, String accession, int sign,
            BigDecimal value, BigDecimal operatingIncome, UUID sourceArtifactId) {}
}
