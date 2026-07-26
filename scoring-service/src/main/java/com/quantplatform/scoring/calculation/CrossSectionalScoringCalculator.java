package com.quantplatform.scoring.calculation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Component;

@Component
public class CrossSectionalScoringCalculator {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;
    private static final BigDecimal FACTOR_COUNT = BigDecimal.valueOf(3);
    private static final int STORAGE_SCALE = 6;

    public List<FactorScore> calculate(List<RawFactorMetrics> universe) {
        if (universe == null || universe.isEmpty()) {
            return List.of();
        }

        var valueZScores = zScores(universe, RawFactorMetrics::value);
        var momentumZScores = zScores(universe, RawFactorMetrics::momentum);
        var qualityZScores = zScores(universe, RawFactorMetrics::quality);
        var scores = new ArrayList<FactorScore>(universe.size());

        for (int index = 0; index < universe.size(); index++) {
            var metrics = universe.get(index);
            var zValue = valueZScores.get(index);
            var zMomentum = momentumZScores.get(index);
            var zQuality = qualityZScores.get(index);
            var composite = zValue.add(zMomentum, MATH_CONTEXT)
                    .add(zQuality, MATH_CONTEXT)
                    .divide(FACTOR_COUNT, MATH_CONTEXT);
            scores.add(new FactorScore(
                    metrics.symbol(),
                    store(composite),
                    store(zValue),
                    store(zMomentum),
                    store(zQuality)));
        }
        return List.copyOf(scores);
    }

    private List<BigDecimal> zScores(
            List<RawFactorMetrics> universe,
            Function<RawFactorMetrics, BigDecimal> metric
    ) {
        var count = BigDecimal.valueOf(universe.size());
        var mean = universe.stream()
                .map(metric)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MATH_CONTEXT))
                .divide(count, MATH_CONTEXT);
        var variance = universe.stream()
                .map(metric)
                .map(value -> value.subtract(mean, MATH_CONTEXT))
                .map(deviation -> deviation.multiply(deviation, MATH_CONTEXT))
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MATH_CONTEXT))
                .divide(count, MATH_CONTEXT);

        if (variance.signum() == 0) {
            return universe.stream().map(ignored -> BigDecimal.ZERO).toList();
        }

        var standardDeviation = variance.sqrt(MATH_CONTEXT);
        return universe.stream()
                .map(metric)
                .map(value -> value.subtract(mean, MATH_CONTEXT)
                        .divide(standardDeviation, MATH_CONTEXT))
                .toList();
    }

    private BigDecimal store(BigDecimal value) {
        return value.setScale(STORAGE_SCALE, RoundingMode.HALF_UP);
    }
}
