package com.quantplatform.scoring.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketBarRepository
        extends JpaRepository<MarketBarEntity, TemporalSymbolId> {

    Optional<MarketBarEntity> findFirstBySymbolAndTimeLessThanEqualOrderByTimeDesc(
            String symbol,
            Instant time
    );
}
