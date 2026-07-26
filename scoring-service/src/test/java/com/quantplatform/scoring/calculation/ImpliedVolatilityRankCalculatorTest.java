package com.quantplatform.scoring.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ImpliedVolatilityRankCalculatorTest {

    private final ImpliedVolatilityRankCalculator calculator =
            new ImpliedVolatilityRankCalculator();

    @Test
    void appliesTheRequiredFiftyTwoWeekFormula() {
        var rank = calculator.calculate(
                new BigDecimal("0.25"),
                new BigDecimal("0.10"),
                new BigDecimal("0.30"));

        assertThat(rank).isEqualByComparingTo("75.000000");
    }

    @Test
    void rejectsADegenerateRange() {
        assertThatThrownBy(() -> calculator.calculate(
                new BigDecimal("0.20"),
                new BigDecimal("0.20"),
                new BigDecimal("0.20")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
