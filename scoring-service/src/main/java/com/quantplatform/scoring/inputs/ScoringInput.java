package com.quantplatform.scoring.inputs;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Immutable raw data contract. Input readiness is not final model eligibility. */
public record ScoringInput(UUID instrumentId, UUID issuerId, String symbol,
        boolean inSp500, boolean inNasdaq100, boolean primaryClass, String profile,
        String sector, String peerGroup, BigDecimal rawClose, BigDecimal adjustedClose,
        BigDecimal momentumRecent, BigDecimal momentumOld, int historyCount, int liquidityCount,
        BigDecimal medianDollarVolume, BigDecimal sharesOutstanding, UUID shareFactId,
        List<Fact> facts, List<PriceSource> priceLineage, List<Reason> reasons) {
    public ScoringInput {
        facts=List.copyOf(facts); priceLineage=List.copyOf(priceLineage); reasons=List.copyOf(reasons);
    }
    public boolean inputReady() { return reasons.isEmpty(); }
    public record Reason(String code, String detail) {}
    public record PriceSource(LocalDate date, String mode, String observationKey, UUID sourceArtifactId) {}
    public record Fact(UUID factId, UUID filingId, String metric, LocalDate start, LocalDate end,
        BigDecimal value, String unit, Instant availableAt, LocalDate filedDate,
        UUID sourceArtifactId, String mappingVersion, String parserVersion) {}
}
