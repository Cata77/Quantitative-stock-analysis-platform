package com.quantplatform.scoring.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.quantplatform.scoring.ingestion.event.MarketDataEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@IdClass(TemporalSymbolId.class)
@Table(name = "fundamental_snapshots")
public class FundamentalSnapshotEntity {

    @Id
    @Column(name = "time", nullable = false)
    private Instant time;

    @Id
    @Column(name = "symbol", nullable = false, length = 10)
    private String symbol;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "company_name", nullable = false, length = 200)
    private String name;

    @Column(name = "asset_type", length = 40)
    private String assetType;

    @Column(name = "exchange", length = 30)
    private String exchange;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "country", length = 80)
    private String country;

    @Column(name = "sector", length = 100)
    private String sector;

    @Column(name = "industry", length = 150)
    private String industry;

    @Column(name = "latest_quarter")
    private LocalDate latestQuarter;

    @Column(name = "market_capitalization", precision = 30, scale = 6)
    private BigDecimal marketCapitalization;

    @Column(name = "revenue_ttm", precision = 30, scale = 6)
    private BigDecimal revenueTtm;

    @Column(name = "ebitda", precision = 30, scale = 6)
    private BigDecimal ebitda;

    @Column(name = "pe_ratio", precision = 20, scale = 8)
    private BigDecimal peRatio;

    @Column(name = "peg_ratio", precision = 20, scale = 8)
    private BigDecimal pegRatio;

    @Column(name = "price_to_book_ratio", precision = 20, scale = 8)
    private BigDecimal priceToBookRatio;

    @Column(name = "earnings_per_share", precision = 20, scale = 8)
    private BigDecimal earningsPerShare;

    @Column(name = "profit_margin", precision = 20, scale = 8)
    private BigDecimal profitMargin;

    @Column(name = "operating_margin_ttm", precision = 20, scale = 8)
    private BigDecimal operatingMarginTtm;

    @Column(name = "return_on_assets_ttm", precision = 20, scale = 8)
    private BigDecimal returnOnAssetsTtm;

    @Column(name = "return_on_equity_ttm", precision = 20, scale = 8)
    private BigDecimal returnOnEquityTtm;

    @Column(name = "quarterly_revenue_growth_yoy", precision = 20, scale = 8)
    private BigDecimal quarterlyRevenueGrowthYoy;

    @Column(name = "quarterly_earnings_growth_yoy", precision = 20, scale = 8)
    private BigDecimal quarterlyEarningsGrowthYoy;

    @Column(name = "analyst_target_price", precision = 20, scale = 8)
    private BigDecimal analystTargetPrice;

    @Column(name = "beta", precision = 20, scale = 8)
    private BigDecimal beta;

    protected FundamentalSnapshotEntity() {
    }

    private FundamentalSnapshotEntity(MarketDataEvent event) {
        var fundamentals = event.fundamentals();
        time = event.observedAt();
        symbol = event.symbol();
        provider = event.provider();
        name = fundamentals.name();
        assetType = fundamentals.assetType();
        exchange = fundamentals.exchange();
        currency = fundamentals.currency();
        country = fundamentals.country();
        sector = fundamentals.sector();
        industry = fundamentals.industry();
        latestQuarter = fundamentals.latestQuarter();
        marketCapitalization = fundamentals.marketCapitalization();
        revenueTtm = fundamentals.revenueTtm();
        ebitda = fundamentals.ebitda();
        peRatio = fundamentals.peRatio();
        pegRatio = fundamentals.pegRatio();
        priceToBookRatio = fundamentals.priceToBookRatio();
        earningsPerShare = fundamentals.earningsPerShare();
        profitMargin = fundamentals.profitMargin();
        operatingMarginTtm = fundamentals.operatingMarginTtm();
        returnOnAssetsTtm = fundamentals.returnOnAssetsTtm();
        returnOnEquityTtm = fundamentals.returnOnEquityTtm();
        quarterlyRevenueGrowthYoy = fundamentals.quarterlyRevenueGrowthYoy();
        quarterlyEarningsGrowthYoy = fundamentals.quarterlyEarningsGrowthYoy();
        analystTargetPrice = fundamentals.analystTargetPrice();
        beta = fundamentals.beta();
    }

    public static FundamentalSnapshotEntity from(MarketDataEvent event) {
        return new FundamentalSnapshotEntity(event);
    }

    public Instant getTime() {
        return time;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }

    public String getExchange() {
        return exchange;
    }

    public String getCountry() {
        return country;
    }

    public String getSector() {
        return sector;
    }

    public String getIndustry() {
        return industry;
    }

    public BigDecimal getPeRatio() {
        return peRatio;
    }

    public BigDecimal getReturnOnEquityTtm() {
        return returnOnEquityTtm;
    }
}
