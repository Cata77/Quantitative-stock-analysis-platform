package com.quantplatform.marketdata.operations;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ingestion.prices")
public record DailyPriceProperties(@DefaultValue("sip") String feed, @DefaultValue("100") int symbolsPerRequest,
        @DefaultValue("10000") int pageLimit, @DefaultValue("350ms") Duration requestSpacing,
        @DefaultValue("16m") Duration publicationDelay, @DefaultValue("true") boolean adjustedEnabled,
        @DefaultValue("true") boolean corporateActionsEnabled, @DefaultValue("14") int actionsRefreshDays) {
    public DailyPriceProperties {
        if (!Set.of("sip","iex").contains(feed) || symbolsPerRequest < 1 || symbolsPerRequest > 200
                || pageLimit < 1 || pageLimit > 10000 || requestSpacing.compareTo(Duration.ofMillis(300)) < 0
                || publicationDelay.compareTo(Duration.ofMinutes(15)) < 0 || actionsRefreshDays < 1 || actionsRefreshDays > 90)
            throw new IllegalArgumentException("invalid daily price provider limits");
    }
}
