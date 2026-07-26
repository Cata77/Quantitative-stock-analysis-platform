package com.quantplatform.scoring.persistence;

import java.math.BigDecimal;
import java.time.Instant;

import com.quantplatform.scoring.calculation.FactorScore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@IdClass(TemporalSymbolId.class)
@Table(name = "factor_scores")
public class FactorScoreEntity {

    @Id
    @Column(name = "time", nullable = false)
    private Instant time;

    @Id
    @Column(name = "symbol", nullable = false, length = 10)
    private String symbol;

    @Column(name = "composite_score", nullable = false, precision = 10, scale = 6)
    private BigDecimal compositeScore;

    @Column(name = "z_value", nullable = false, precision = 10, scale = 6)
    private BigDecimal zValue;

    @Column(name = "z_momentum", nullable = false, precision = 10, scale = 6)
    private BigDecimal zMomentum;

    @Column(name = "z_quality", nullable = false, precision = 10, scale = 6)
    private BigDecimal zQuality;

    protected FactorScoreEntity() {
    }

    private FactorScoreEntity(Instant time, FactorScore score) {
        this.time = time;
        symbol = score.symbol();
        compositeScore = score.composite();
        zValue = score.zValue();
        zMomentum = score.zMomentum();
        zQuality = score.zQuality();
    }

    public static FactorScoreEntity from(Instant time, FactorScore score) {
        return new FactorScoreEntity(time, score);
    }

    public Instant getTime() {
        return time;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getCompositeScore() {
        return compositeScore;
    }

    public BigDecimal getZValue() {
        return zValue;
    }

    public BigDecimal getZMomentum() {
        return zMomentum;
    }

    public BigDecimal getZQuality() {
        return zQuality;
    }
}
