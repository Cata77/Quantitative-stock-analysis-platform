package com.quantplatform.scoring.search;

import java.time.Instant;

public record CompanyMetadataChanged(
        String symbol,
        String name,
        String exchange,
        String country,
        String sector,
        String industry,
        Instant observedAt
) {
}
