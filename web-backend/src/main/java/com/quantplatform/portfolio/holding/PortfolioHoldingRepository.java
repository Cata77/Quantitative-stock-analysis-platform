package com.quantplatform.portfolio.holding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioHoldingRepository extends JpaRepository<PortfolioHolding, UUID> {

    List<PortfolioHolding> findAllByUserIdOrderByPurchasedAtDesc(UUID userId);

    Optional<PortfolioHolding> findByIdAndUserId(UUID id, UUID userId);
}
