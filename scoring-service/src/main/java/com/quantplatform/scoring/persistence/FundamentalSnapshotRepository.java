package com.quantplatform.scoring.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FundamentalSnapshotRepository
        extends JpaRepository<FundamentalSnapshotEntity, TemporalSymbolId> {

    @Query("select distinct f.symbol from FundamentalSnapshotEntity f order by f.symbol")
    List<String> findDistinctSymbols();

    Optional<FundamentalSnapshotEntity>
            findFirstBySymbolAndTimeLessThanEqualOrderByTimeDesc(String symbol, Instant time);
}
