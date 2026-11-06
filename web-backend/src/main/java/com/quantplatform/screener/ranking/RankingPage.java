package com.quantplatform.screener.ranking;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record RankingPage(LocalDate requestedAsOf, Map<String,Object> run, String freshness,
        Long ageDays, int maximumAgeDays,
        String wording, int page, int size, long totalElements, int totalPages,
        List<Map<String,Object>> content) {}
