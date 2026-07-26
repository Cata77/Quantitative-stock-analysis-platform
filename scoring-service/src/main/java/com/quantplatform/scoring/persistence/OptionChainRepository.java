package com.quantplatform.scoring.persistence;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OptionChainRepository
        extends JpaRepository<OptionChainEntity, TemporalSymbolId> {

    @Query("""
            select min(o.impliedVolatility)
            from OptionChainEntity o
            where o.underlying = :underlying
              and o.time >= :since
              and o.time <= :asOf
            """)
    BigDecimal findMinimumImpliedVolatility(
            @Param("underlying") String underlying,
            @Param("since") Instant since,
            @Param("asOf") Instant asOf
    );

    @Query("""
            select max(o.impliedVolatility)
            from OptionChainEntity o
            where o.underlying = :underlying
              and o.time >= :since
              and o.time <= :asOf
            """)
    BigDecimal findMaximumImpliedVolatility(
            @Param("underlying") String underlying,
            @Param("since") Instant since,
            @Param("asOf") Instant asOf
    );
}
