package com.quantplatform.screener.ranking;

import java.util.UUID;

public record RankingFilter(Universe universe, UUID modelVersion, String profile, String sector,
        String peerGroup, Eligibility eligibility, String warning, Sort sort, Direction direction,
        String metric, UUID runId, UUID instrumentId) {
    public enum Universe { UNION, SP500, NASDAQ100 }
    public enum Eligibility { ELIGIBLE, EXCLUDED, ALL }
    public enum Sort { COMPOSITE, VALUE, QUALITY, MOMENTUM, CONTRIBUTION }
    public enum Direction { ASC, DESC }
    public RankingFilter {
        if (sort == Sort.CONTRIBUTION && (metric == null || metric.isBlank()))
            throw new IllegalArgumentException("metric is required for contribution sorting");
    }
    public static RankingFilter defaults() {
        return new RankingFilter(Universe.UNION, null, null, null, null, Eligibility.ELIGIBLE,
                null, Sort.COMPOSITE, Direction.DESC, null, null, null);
    }
}
