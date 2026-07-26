package com.quantplatform.scoring.ingestion.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record FundamentalSnapshot(
        String name,
        String assetType,
        String exchange,
        String currency,
        String country,
        String sector,
        String industry,
        LocalDate latestQuarter,
        BigDecimal marketCapitalization,
        BigDecimal revenueTtm,
        BigDecimal ebitda,
        BigDecimal peRatio,
        BigDecimal pegRatio,
        BigDecimal priceToBookRatio,
        BigDecimal earningsPerShare,
        BigDecimal profitMargin,
        BigDecimal operatingMarginTtm,
        BigDecimal returnOnAssetsTtm,
        BigDecimal returnOnEquityTtm,
        BigDecimal quarterlyRevenueGrowthYoy,
        BigDecimal quarterlyEarningsGrowthYoy,
        BigDecimal analystTargetPrice,
        BigDecimal beta
) {

    public FundamentalSnapshot {
        if (Objects.requireNonNull(name, "name must not be null").isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
