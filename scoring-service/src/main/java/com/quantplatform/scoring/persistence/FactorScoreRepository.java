package com.quantplatform.scoring.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FactorScoreRepository
        extends JpaRepository<FactorScoreEntity, TemporalSymbolId> {
}
