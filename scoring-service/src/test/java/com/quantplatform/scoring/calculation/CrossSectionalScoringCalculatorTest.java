package com.quantplatform.scoring.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class CrossSectionalScoringCalculatorTest {

    private final CrossSectionalScoringCalculator calculator =
            new CrossSectionalScoringCalculator();

    @Test
    void appliesPopulationZScoresAndArithmeticComposite() {
        var scores = calculator.calculate(List.of(
                metrics("AAA", "1"),
                metrics("BBB", "2"),
                metrics("CCC", "3")));

        assertThat(scores).extracting(FactorScore::zValue)
                .containsExactly(
                        new BigDecimal("-1.224745"),
                        new BigDecimal("0.000000"),
                        new BigDecimal("1.224745"));
        assertThat(scores).extracting(FactorScore::composite)
                .containsExactly(
                        new BigDecimal("-1.224745"),
                        new BigDecimal("0.000000"),
                        new BigDecimal("1.224745"));
    }

    @Test
    void returnsZeroForAZeroVarianceFactor() {
        var scores = calculator.calculate(List.of(
                metrics("AAA", "5"),
                metrics("BBB", "5")));

        assertThat(scores).allSatisfy(score -> {
            assertThat(score.zValue()).isEqualByComparingTo("0");
            assertThat(score.composite()).isEqualByComparingTo("0");
        });
    }

    private RawFactorMetrics metrics(String symbol, String value) {
        var metric = new BigDecimal(value);
        return new RawFactorMetrics(symbol, metric, metric, metric);
    }
}
