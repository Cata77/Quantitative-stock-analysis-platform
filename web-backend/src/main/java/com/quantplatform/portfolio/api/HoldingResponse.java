package com.quantplatform.portfolio.api;

import com.quantplatform.portfolio.holding.PortfolioHolding;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record HoldingResponse(
        UUID id,
        String ticker,
        BigDecimal quantity,
        BigDecimal entryPrice,
        Instant purchasedAt
) {

    public static HoldingResponse from(PortfolioHolding holding) {
        return new HoldingResponse(
                holding.getId(),
                holding.getTicker(),
                holding.getQuantity(),
                holding.getEntryPrice(),
                holding.getPurchasedAt());
    }
}
