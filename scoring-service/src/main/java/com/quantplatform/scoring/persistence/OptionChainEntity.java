package com.quantplatform.scoring.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;

import com.quantplatform.scoring.ingestion.event.MarketDataEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@IdClass(TemporalSymbolId.class)
@Table(name = "option_chain")
public class OptionChainEntity {

    @Id
    @Column(name = "time", nullable = false)
    private Instant time;

    @Id
    @Column(name = "symbol", nullable = false, length = 30)
    private String symbol;

    @Column(name = "underlying", nullable = false, length = 10)
    private String underlying;

    @Column(name = "expiry", nullable = false)
    private Instant expiry;

    @Column(name = "strike", nullable = false, precision = 20, scale = 8)
    private BigDecimal strike;

    @Column(name = "option_type", nullable = false, length = 5)
    private String optionType;

    @Column(name = "ltp", nullable = false, precision = 20, scale = 8)
    private BigDecimal lastPrice;

    @Column(name = "bid", nullable = false, precision = 20, scale = 8)
    private BigDecimal bid;

    @Column(name = "ask", nullable = false, precision = 20, scale = 8)
    private BigDecimal ask;

    @Column(name = "volume", nullable = false)
    private long volume;

    @Column(name = "oi", nullable = false)
    private long openInterest;

    @Column(name = "iv", nullable = false, precision = 20, scale = 8)
    private BigDecimal impliedVolatility;

    @Column(name = "iv_rank", precision = 10, scale = 6)
    private BigDecimal impliedVolatilityRank;

    @Column(name = "delta", nullable = false, precision = 20, scale = 8)
    private BigDecimal delta;

    @Column(name = "gamma", nullable = false, precision = 20, scale = 8)
    private BigDecimal gamma;

    @Column(name = "theta", nullable = false, precision = 20, scale = 8)
    private BigDecimal theta;

    @Column(name = "vega", nullable = false, precision = 20, scale = 8)
    private BigDecimal vega;

    @Column(name = "rho", nullable = false, precision = 20, scale = 8)
    private BigDecimal rho;

    protected OptionChainEntity() {
    }

    private OptionChainEntity(MarketDataEvent event, BigDecimal impliedVolatilityRank) {
        var option = event.option();
        time = option.time();
        symbol = option.contractSymbol();
        underlying = event.symbol();
        expiry = option.expiration().atStartOfDay().toInstant(ZoneOffset.UTC);
        strike = option.strike();
        optionType = option.optionType().name();
        lastPrice = option.lastPrice();
        bid = option.bid();
        ask = option.ask();
        volume = option.volume();
        openInterest = option.openInterest();
        impliedVolatility = option.impliedVolatility();
        this.impliedVolatilityRank = impliedVolatilityRank;
        delta = option.delta();
        gamma = option.gamma();
        theta = option.theta();
        vega = option.vega();
        rho = option.rho();
    }

    public static OptionChainEntity from(
            MarketDataEvent event,
            BigDecimal impliedVolatilityRank
    ) {
        return new OptionChainEntity(event, impliedVolatilityRank);
    }

    public Instant getTime() {
        return time;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getImpliedVolatilityRank() {
        return impliedVolatilityRank;
    }
}
