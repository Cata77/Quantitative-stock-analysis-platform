package com.quantplatform.portfolio.holding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "portfolios")
public class PortfolioHolding {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Column(name = "entry_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "purchased_at", nullable = false)
    private Instant purchasedAt;

    protected PortfolioHolding() {
    }

    public PortfolioHolding(
            UUID userId,
            String ticker,
            BigDecimal quantity,
            BigDecimal entryPrice,
            Instant purchasedAt
    ) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        update(ticker, quantity, entryPrice, purchasedAt);
    }

    public void update(
            String ticker,
            BigDecimal quantity,
            BigDecimal entryPrice,
            Instant purchasedAt
    ) {
        this.ticker = ticker;
        this.quantity = quantity;
        this.entryPrice = entryPrice;
        this.purchasedAt = purchasedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTicker() {
        return ticker;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public Instant getPurchasedAt() {
        return purchasedAt;
    }
}
