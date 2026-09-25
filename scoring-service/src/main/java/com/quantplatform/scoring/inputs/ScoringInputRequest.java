package com.quantplatform.scoring.inputs;

import java.time.*;
import java.util.*;

/** Exact economic date, knowledge boundary and explicit versioned timing policy. */
public record ScoringInputRequest(LocalDate scoreDate, Instant marketCutoff, Instant knowledgeCutoff,
        UUID sp500Snapshot, UUID nasdaq100Snapshot, UUID rawDataset, UUID adjustedDataset,
        LocalDate adjustmentBasis, UUID classificationVersion, String mappingVersion, Set<String> requiredMetrics,
        String timingPolicy) {
    public static final String CLOSE = "CLOSE_V1";
    public static final String PRE_OPEN = "NEXT_OPEN_MINUS_30M_V1";
    public ScoringInputRequest(LocalDate scoreDate, Instant marketCutoff, Instant knowledgeCutoff,
            UUID sp500Snapshot, UUID nasdaq100Snapshot, UUID rawDataset, UUID adjustedDataset,
            LocalDate adjustmentBasis, UUID classificationVersion, String mappingVersion, Set<String> requiredMetrics) {
        this(scoreDate,marketCutoff,knowledgeCutoff,sp500Snapshot,nasdaq100Snapshot,rawDataset,adjustedDataset,
            adjustmentBasis,classificationVersion,mappingVersion,requiredMetrics,CLOSE);
    }
    public ScoringInputRequest {
        Objects.requireNonNull(scoreDate); Objects.requireNonNull(marketCutoff); Objects.requireNonNull(knowledgeCutoff);
        Objects.requireNonNull(sp500Snapshot); Objects.requireNonNull(nasdaq100Snapshot);
        Objects.requireNonNull(rawDataset); Objects.requireNonNull(adjustedDataset);
        Objects.requireNonNull(adjustmentBasis); Objects.requireNonNull(classificationVersion);
        validateTiming(marketCutoff,knowledgeCutoff,timingPolicy);
        LocalDate vintage = PRE_OPEN.equals(timingPolicy)
            ? knowledgeCutoff.atZone(ZoneId.of("America/New_York")).toLocalDate() : scoreDate;
        if (adjustmentBasis.isAfter(vintage) || (PRE_OPEN.equals(timingPolicy) && !adjustmentBasis.equals(vintage))
                || !scoreDate.equals(marketCutoff.atZone(ZoneId.of("America/New_York")).toLocalDate())
                || sp500Snapshot.equals(nasdaq100Snapshot) || !"sec-us-gaap-v1".equals(mappingVersion))
            throw new IllegalArgumentException("Invalid scoring cutoff, snapshot pair or mapping version");
        requiredMetrics=Set.copyOf(requiredMetrics);
        if(requiredMetrics.isEmpty() || requiredMetrics.stream().anyMatch(m->!m.matches("[A-Z][A-Z0-9_]{0,79}")))
            throw new IllegalArgumentException("Declare required canonical metrics explicitly");
    }
    public static void validateTiming(Instant market, Instant knowledge, String policy) {
        boolean valid = CLOSE.equals(policy) ? !knowledge.isAfter(market)
            : PRE_OPEN.equals(policy) && knowledge.isAfter(market) && !knowledge.isAfter(market.plus(Duration.ofDays(7)));
        if (!valid) throw new IllegalArgumentException("Invalid knowledge cutoff for timing policy");
        // Exact next-open/calendar validation is performed by the SQL and publication boundary.
    }
}
