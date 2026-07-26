package com.quantplatform.screener.ranking;

import java.time.Instant;
import java.util.List;

public record RankingPage(
        Instant requestedAsOf,
        Instant scoreTime,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<RankingItem> content
) {

    public static RankingPage empty(Instant requestedAsOf, int page, int size) {
        return new RankingPage(
                requestedAsOf,
                null,
                page,
                size,
                0,
                0,
                List.of());
    }
}
