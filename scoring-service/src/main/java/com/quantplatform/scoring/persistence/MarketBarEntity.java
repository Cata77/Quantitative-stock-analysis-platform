package com.quantplatform.scoring.persistence;

import java.math.BigDecimal;
import java.time.Instant;

import com.quantplatform.scoring.ingestion.event.MarketDataEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@IdClass(TemporalSymbolId.class)
@Table(name = "market_bars")
public class MarketBarEntity {

    @Id
    @Column(name = "time", nullable = false)
    private Instant time;

    @Id
    @Column(name = "symbol", nullable = false, length = 10)
    private String symbol;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "open_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal open;

    @Column(name = "high_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal high;

    @Column(name = "low_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal low;

    @Column(name = "close_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal close;

    @Column(name = "volume", nullable = false)
    private long volume;

    @Column(name = "vwap", precision = 20, scale = 8)
    private BigDecimal volumeWeightedAveragePrice;

    @Column(name = "trade_count", nullable = false)
    private long tradeCount;

    protected MarketBarEntity() {
    }

    private MarketBarEntity(MarketDataEvent event) {
        var bar = event.stockBar();
        time = bar.time();
        symbol = event.symbol();
        provider = event.provider();
        open = bar.open();
        high = bar.high();
        low = bar.low();
        close = bar.close();
        volume = bar.volume();
        volumeWeightedAveragePrice = bar.volumeWeightedAveragePrice();
        tradeCount = bar.tradeCount();
    }

    public static MarketBarEntity from(MarketDataEvent event) {
        return new MarketBarEntity(event);
    }

    public Instant getTime() {
        return time;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getClose() {
        return close;
    }
}
