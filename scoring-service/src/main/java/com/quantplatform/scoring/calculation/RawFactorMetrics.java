package com.quantplatform.scoring.calculation;

import java.math.BigDecimal;
import java.util.Objects;

public record RawFactorMetrics(
        String symbol,
        BigDecimal value,
        BigDecimal momentum,
        BigDecimal quality
) {

    public RawFactorMetrics {
        if (Objects.requireNonNull(symbol, "symbol must not be null").isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(momentum, "momentum must not be null");
        Objects.requireNonNull(quality, "quality must not be null");
    }
}
