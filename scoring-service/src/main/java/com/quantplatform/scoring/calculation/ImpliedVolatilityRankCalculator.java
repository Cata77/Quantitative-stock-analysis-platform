package com.quantplatform.scoring.calculation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public class ImpliedVolatilityRankCalculator {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public BigDecimal calculate(
            BigDecimal current,
            BigDecimal minimum52Week,
            BigDecimal maximum52Week
    ) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(minimum52Week, "minimum52Week must not be null");
        Objects.requireNonNull(maximum52Week, "maximum52Week must not be null");
        if (maximum52Week.compareTo(minimum52Week) <= 0) {
            throw new IllegalArgumentException(
                    "maximum52Week must be greater than minimum52Week");
        }
        return current.subtract(minimum52Week, MathContext.DECIMAL128)
                .divide(
                        maximum52Week.subtract(minimum52Week, MathContext.DECIMAL128),
                        MathContext.DECIMAL128)
                .multiply(ONE_HUNDRED, MathContext.DECIMAL128)
                .setScale(6, RoundingMode.HALF_UP);
    }
}
