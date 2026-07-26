package com.quantplatform.scoring.ingestion.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record StockBar(
        Instant time,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        BigDecimal volumeWeightedAveragePrice,
        long tradeCount
) {

    public StockBar {
        Objects.requireNonNull(time, "time must not be null");
        requirePositive(open, "open");
        requirePositive(high, "high");
        requirePositive(low, "low");
        requirePositive(close, "close");
        if (high.compareTo(low) < 0) {
            throw new IllegalArgumentException("high must be greater than or equal to low");
        }
        if (volume < 0 || tradeCount < 0) {
            throw new IllegalArgumentException("volume and tradeCount must not be negative");
        }
        if (volumeWeightedAveragePrice != null
                && volumeWeightedAveragePrice.signum() <= 0) {
            throw new IllegalArgumentException(
                    "volumeWeightedAveragePrice must be positive when present");
        }
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
