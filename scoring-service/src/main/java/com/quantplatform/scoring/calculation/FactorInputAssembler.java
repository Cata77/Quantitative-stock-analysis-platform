package com.quantplatform.scoring.calculation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.quantplatform.scoring.config.ScoringProperties;
import com.quantplatform.scoring.persistence.FundamentalSnapshotRepository;
import com.quantplatform.scoring.persistence.MarketBarRepository;

@Component
public class FactorInputAssembler {

    private final FundamentalSnapshotRepository fundamentalRepository;
    private final MarketBarRepository marketBarRepository;
    private final ScoringProperties properties;

    public FactorInputAssembler(
            FundamentalSnapshotRepository fundamentalRepository,
            MarketBarRepository marketBarRepository,
            ScoringProperties properties
    ) {
        this.fundamentalRepository = fundamentalRepository;
        this.marketBarRepository = marketBarRepository;
        this.properties = properties;
    }

    public List<RawFactorMetrics> assemble(Instant asOf) {
        var referenceTime = asOf.minus(properties.momentumLookback());
        var metrics = new ArrayList<RawFactorMetrics>();

        for (var symbol : fundamentalRepository.findDistinctSymbols()) {
            var fundamentals = fundamentalRepository
                    .findFirstBySymbolAndTimeLessThanEqualOrderByTimeDesc(symbol, asOf);
            var latestBar = marketBarRepository
                    .findFirstBySymbolAndTimeLessThanEqualOrderByTimeDesc(symbol, asOf);
            var referenceBar = marketBarRepository
                    .findFirstBySymbolAndTimeLessThanEqualOrderByTimeDesc(symbol, referenceTime);

            if (fundamentals.isEmpty() || latestBar.isEmpty() || referenceBar.isEmpty()) {
                continue;
            }

            var snapshot = fundamentals.orElseThrow();
            var peRatio = snapshot.getPeRatio();
            var quality = snapshot.getReturnOnEquityTtm();
            if (peRatio == null || peRatio.signum() <= 0 || quality == null
                    || !latestBar.orElseThrow().getTime()
                            .isAfter(referenceBar.orElseThrow().getTime())) {
                continue;
            }

            var value = BigDecimal.ONE.divide(peRatio, MathContext.DECIMAL128);
            var momentum = latestBar.orElseThrow().getClose()
                    .divide(referenceBar.orElseThrow().getClose(), MathContext.DECIMAL128)
                    .subtract(BigDecimal.ONE, MathContext.DECIMAL128);
            metrics.add(new RawFactorMetrics(symbol, value, momentum, quality));
        }
        return List.copyOf(metrics);
    }
}
