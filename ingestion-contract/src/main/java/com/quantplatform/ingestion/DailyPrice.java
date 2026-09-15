package com.quantplatform.ingestion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

public record DailyPrice(Instant time, LocalDate sessionDate, String currency, String feed,
        LocalDate adjustmentAsOf, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
        long volume, BigDecimal volumeWeightedAveragePrice, long tradeCount) {
    public DailyPrice {
        Objects.requireNonNull(time);
        Objects.requireNonNull(sessionDate);
        if (!sessionDate.equals(LocalDate.ofInstant(time, ZoneId.of("America/New_York"))))
            throw new IllegalArgumentException("bar timestamp is outside its session");
        if (currency == null || !currency.matches("[A-Z]{3}") || !Set.of("sip","iex").contains(feed))
            throw new IllegalArgumentException("currency and supported feed are required");
        for (var price : new BigDecimal[] {open, high, low, close})
            if (price == null || price.signum() <= 0) throw new IllegalArgumentException("prices must be positive");
        if (high.compareTo(low) < 0 || high.compareTo(open) < 0 || high.compareTo(close) < 0
                || low.compareTo(open) > 0 || low.compareTo(close) > 0 || volume < 0 || tradeCount < 0
                || (volumeWeightedAveragePrice != null && volumeWeightedAveragePrice.signum() <= 0))
            throw new IllegalArgumentException("invalid OHLCV bounds");
        if (adjustmentAsOf != null && adjustmentAsOf.isBefore(sessionDate))
            throw new IllegalArgumentException("adjustment vintage precedes session");
    }

    public void validateAdjustment(String adjustment) {
        if (!(adjustment.equals("RAW") && adjustmentAsOf == null
                || adjustment.equals("SPLIT_DIVIDEND") && adjustmentAsOf != null))
            throw new IllegalArgumentException("adjustment and vintage disagree");
    }
}
