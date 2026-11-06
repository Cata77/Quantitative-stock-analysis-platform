package com.quantplatform.screener.ranking;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RankingService {
    private final Clock clock;
    private final RankingQueryRepository repository;
    public RankingService(Clock clock, RankingQueryRepository repository) {
        this.clock=clock; this.repository=repository;
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public RankingPage findRankings(LocalDate requestedAsOf,int page,int size,RankingFilter filter) {
        LocalDate asOf=requestedAsOf==null?LocalDate.now(clock):requestedAsOf;
        var selected=repository.latest(asOf,filter.modelVersion(),filter.runId());
        if(selected.isEmpty()) return new RankingPage(asOf,null,"UNAVAILABLE",null,35,"Highest-ranked candidates",
                page,size,0,0,List.of());
        var run=selected.orElseThrow();
        UUID id=UUID.fromString(run.get("id").toString());
        long count=repository.count(id,filter);
        LocalDate date=LocalDate.parse(run.get("asOfDate").toString());
        // Explicit age policy accommodates month length and market holidays; it is not data completeness.
        long ageDays=java.time.temporal.ChronoUnit.DAYS.between(date,asOf);
        String freshness=ageDays>35?"STALE":"CURRENT";
        return new RankingPage(asOf,run,freshness,ageDays,35,"Highest-ranked candidates",page,size,count,
                (int)((count+size-1)/size),repository.page(id,filter,size,(long)page*size));
    }
}
