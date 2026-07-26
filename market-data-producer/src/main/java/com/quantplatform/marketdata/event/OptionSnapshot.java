package com.quantplatform.marketdata.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record OptionSnapshot(
        String contractSymbol,
        Instant time,
        LocalDate expiration,
        BigDecimal strike,
        OptionType optionType,
        BigDecimal lastPrice,
        BigDecimal bid,
        BigDecimal ask,
        long volume,
        long openInterest,
        BigDecimal impliedVolatility,
        BigDecimal delta,
        BigDecimal gamma,
        BigDecimal theta,
        BigDecimal vega,
        BigDecimal rho
) {

    public OptionSnapshot {
        if (Objects.requireNonNull(contractSymbol, "contractSymbol must not be null").isBlank()) {
            throw new IllegalArgumentException("contractSymbol must not be blank");
        }
        Objects.requireNonNull(time, "time must not be null");
        Objects.requireNonNull(expiration, "expiration must not be null");
        Objects.requireNonNull(optionType, "optionType must not be null");
        requirePositive(strike, "strike");
        requireNonNegative(lastPrice, "lastPrice");
        requireNonNegative(bid, "bid");
        requireNonNegative(ask, "ask");
        requireNonNegative(impliedVolatility, "impliedVolatility");
        Objects.requireNonNull(delta, "delta must not be null");
        Objects.requireNonNull(gamma, "gamma must not be null");
        Objects.requireNonNull(theta, "theta must not be null");
        Objects.requireNonNull(vega, "vega must not be null");
        Objects.requireNonNull(rho, "rho must not be null");
        if (volume < 0 || openInterest < 0) {
            throw new IllegalArgumentException("volume and openInterest must not be negative");
        }
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
