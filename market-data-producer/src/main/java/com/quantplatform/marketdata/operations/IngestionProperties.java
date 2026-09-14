package com.quantplatform.marketdata.operations;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ingestion")
public record IngestionProperties(@DefaultValue("catch-up-and-serve") String mode,
        LocalDate startDate, LocalDate endDate, String requestId, String reason,
        @DefaultValue("true") boolean schedulesEnabled,
        @DefaultValue("50") int itemsPerCycle,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("2m") Duration jobLease,
        @DefaultValue("5s") Duration retryBackoff,
        @DefaultValue("10m") Duration syncTimeout,
        @DefaultValue("https://paper-api.alpaca.markets") String calendarBaseUrl,
        @DefaultValue("scoring-service-v2") String canonicalConsumer) {
    public IngestionProperties {
        if (!Set.of("catch-up-and-serve", "sync-and-exit", "backfill", "force-refresh").contains(mode)
                || itemsPerCycle < 1 || itemsPerCycle > 1000 || maxAttempts < 1 || maxAttempts > 100
                || jobLease.compareTo(Duration.ofSeconds(30)) < 0 || retryBackoff.isNegative() || retryBackoff.isZero()
                || syncTimeout.isNegative() || syncTimeout.isZero()) throw new IllegalArgumentException("invalid ingestion settings");
        if (Set.of("backfill", "force-refresh").contains(mode)
                && (startDate == null || endDate == null)) throw new IllegalArgumentException("bounded start/end dates are required");
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) throw new IllegalArgumentException("invalid ingestion window");
        if (mode.equals("force-refresh") && (requestId == null || requestId.isBlank() || reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("force-refresh needs a stable request-id and audit reason");
        }
    }
}
