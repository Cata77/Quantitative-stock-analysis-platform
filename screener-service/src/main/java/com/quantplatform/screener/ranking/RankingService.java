package com.quantplatform.screener.ranking;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RankingService {

    private final Clock clock;
    private final RankingQueryRepository repository;

    public RankingService(Clock clock, RankingQueryRepository repository) {
        this.clock = clock;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public RankingPage findRankings(Instant requestedAsOf, int page, int size) {
        var asOf = requestedAsOf == null ? clock.instant() : requestedAsOf;
        var scoreTime = repository.findLatestBatchAtOrBefore(asOf);
        if (scoreTime.isEmpty()) {
            return RankingPage.empty(asOf, page, size);
        }

        var batchTime = scoreTime.orElseThrow();
        var totalElements = repository.countBatch(batchTime);
        var totalPages = (int) Math.ceil((double) totalElements / size);
        var content = repository.findPage(batchTime, size, (long) page * size);
        return new RankingPage(
                asOf,
                batchTime,
                page,
                size,
                totalElements,
                totalPages,
                content);
    }
}
