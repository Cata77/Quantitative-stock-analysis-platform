package com.quantplatform.scoring.inputs;

import java.time.*;
import java.util.*;

/** Exact reproducibility inputs; this API supports strict historical knowledge only. */
public record ScoringInputRequest(LocalDate scoreDate, Instant marketCutoff, Instant knowledgeCutoff,
        UUID sp500Snapshot, UUID nasdaq100Snapshot, UUID rawDataset, UUID adjustedDataset,
        LocalDate adjustmentBasis, UUID classificationVersion, String mappingVersion, Set<String> requiredMetrics) {
    public ScoringInputRequest {
        Objects.requireNonNull(scoreDate); Objects.requireNonNull(marketCutoff); Objects.requireNonNull(knowledgeCutoff);
        Objects.requireNonNull(sp500Snapshot); Objects.requireNonNull(nasdaq100Snapshot);
        Objects.requireNonNull(rawDataset); Objects.requireNonNull(adjustedDataset);
        Objects.requireNonNull(adjustmentBasis); Objects.requireNonNull(classificationVersion);
        if (knowledgeCutoff.isAfter(marketCutoff) || adjustmentBasis.isAfter(scoreDate)
                || !scoreDate.equals(marketCutoff.atZone(ZoneId.of("America/New_York")).toLocalDate())
                || sp500Snapshot.equals(nasdaq100Snapshot) || !"sec-us-gaap-v1".equals(mappingVersion))
            throw new IllegalArgumentException("Invalid scoring cutoff, snapshot pair or mapping version");
        requiredMetrics=Set.copyOf(requiredMetrics);
        if(requiredMetrics.isEmpty() || requiredMetrics.stream().anyMatch(m->!m.matches("[A-Z][A-Z0-9_]{0,79}")))
            throw new IllegalArgumentException("Declare required canonical metrics explicitly");
    }
}
