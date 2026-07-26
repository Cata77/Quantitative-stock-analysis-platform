package com.quantplatform.screener.ranking;

import java.math.BigDecimal;

public record RankingItem(
        long rank,
        String symbol,
        BigDecimal compositeScore,
        BigDecimal zValue,
        BigDecimal zMomentum,
        BigDecimal zQuality
) {
}
