package com.quantplatform.screener.sectors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Comparison inside one sector of one publication (P2b.1). Published scores, the global rank and
 * the normalization are unchanged; the sector rank is a separate view over the same run.
 */
public record SectorComparison(Publication publication, String sector, Population population,
        List<Group> groups, List<MetricSummary> metrics, List<Member> members, List<String> warnings,
        Rules rules) {

    public record Publication(UUID runId, LocalDate asOfDate, String modelVersion, UUID classificationVersionId) {}

    /** All members of the sector by classification, and the GENERAL rows normalized as its peer group. */
    public record Population(int members, int eligible, Map<String, Integer> profiles, int peerGroupMembers) {}

    public record Group(String group, String definition, int members, int eligible, boolean smallSample) {}

    /** Raw values of GENERAL eligible members, one row per issuer; the median is not a model input. */
    public record MetricSummary(String metric, String family, int observations, BigDecimal median,
            int normalizedInSector, int normalizedOnFallback, int notNormalized, Integer primaryCohortSize,
            String fallbackCohort, Integer fallbackCohortSize, boolean smallSample) {}

    public record Member(UUID instrumentId, String symbol, String profile, String comparisonGroup,
            boolean eligible, String scoreStatus, BigDecimal compositeScore, Integer globalRank,
            BigDecimal globalPercentile, Integer sectorRank, BigDecimal sectorPercentile,
            Integer sectorPopulation, List<MemberMetric> metrics) {}

    /** {@code normalization}: SECTOR (primary cohort), FALLBACK (profile or price universe) or NONE. */
    public record MemberMetric(String metric, String family, BigDecimal raw, String normalization,
            String cohort, int cohortCount, String reason) {}

    public record Rules(String sectorSource, String ranking, String percentile, int smallSampleBelow,
            String medianPopulation) {}
}
