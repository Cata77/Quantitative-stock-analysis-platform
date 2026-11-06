package com.quantplatform.portfolio.service;

import com.quantplatform.portfolio.holding.PortfolioHolding;
import com.quantplatform.portfolio.holding.PortfolioHoldingRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioService {

    private final PortfolioHoldingRepository holdingRepository;

    public PortfolioService(PortfolioHoldingRepository holdingRepository) {
        this.holdingRepository = holdingRepository;
    }

    @Transactional(readOnly = true)
    public List<PortfolioHolding> findAll(UUID userId) {
        return holdingRepository.findAllByUserIdOrderByPurchasedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public PortfolioHolding find(UUID userId, UUID holdingId) {
        return findOwnedHolding(userId, holdingId);
    }

    @Transactional
    public PortfolioHolding create(
            UUID userId,
            String ticker,
            BigDecimal quantity,
            BigDecimal entryPrice,
            Instant purchasedAt
    ) {
        PortfolioHolding holding = new PortfolioHolding(
                userId,
                normalizeTicker(ticker),
                quantity,
                entryPrice,
                purchasedAt);
        return holdingRepository.save(holding);
    }

    @Transactional
    public PortfolioHolding update(
            UUID userId,
            UUID holdingId,
            String ticker,
            BigDecimal quantity,
            BigDecimal entryPrice,
            Instant purchasedAt
    ) {
        PortfolioHolding holding = findOwnedHolding(userId, holdingId);
        holding.update(normalizeTicker(ticker), quantity, entryPrice, purchasedAt);
        return holding;
    }

    @Transactional
    public void delete(UUID userId, UUID holdingId) {
        holdingRepository.delete(findOwnedHolding(userId, holdingId));
    }

    private PortfolioHolding findOwnedHolding(UUID userId, UUID holdingId) {
        return holdingRepository.findByIdAndUserId(holdingId, userId)
                .orElseThrow(HoldingNotFoundException::new);
    }

    private String normalizeTicker(String ticker) {
        return ticker.toUpperCase(Locale.ROOT);
    }
}
