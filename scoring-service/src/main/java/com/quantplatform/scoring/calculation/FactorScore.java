package com.quantplatform.scoring.calculation;

import java.math.BigDecimal;

public record FactorScore(
        String symbol,
        BigDecimal composite,
        BigDecimal zValue,
        BigDecimal zMomentum,
        BigDecimal zQuality
) {
}
