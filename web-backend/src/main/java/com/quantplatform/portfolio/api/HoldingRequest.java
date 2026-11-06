package com.quantplatform.portfolio.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;

public record HoldingRequest(
        @NotBlank
        @Pattern(
                regexp = "[A-Za-z0-9][A-Za-z0-9.-]{0,9}",
                message = "must be a valid ticker containing at most 10 characters")
        String ticker,

        @NotNull
        @DecimalMin(value = "0.0001", message = "must be greater than zero")
        @Digits(integer = 11, fraction = 4)
        BigDecimal quantity,

        @NotNull
        @DecimalMin(value = "0.0001", message = "must be greater than zero")
        @Digits(integer = 11, fraction = 4)
        BigDecimal entryPrice,

        @NotNull
        @PastOrPresent
        Instant purchasedAt
) {
}
