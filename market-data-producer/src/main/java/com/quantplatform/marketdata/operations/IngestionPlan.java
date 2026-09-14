package com.quantplatform.marketdata.operations;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record IngestionPlan(
        UUID jobDefinitionId,
        UUID sp500SnapshotId,
        UUID nasdaq100SnapshotId,
        LocalDate windowStart,
        LocalDate windowEnd,
        String mode,
        String requestKey,
        String applicationVersion
) {
    public IngestionPlan {
        Objects.requireNonNull(jobDefinitionId, "jobDefinitionId");
        Objects.requireNonNull(sp500SnapshotId, "sp500SnapshotId");
        Objects.requireNonNull(nasdaq100SnapshotId, "nasdaq100SnapshotId");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (windowEnd.isBefore(windowStart)) {
            throw new IllegalArgumentException("windowEnd must not precede windowStart");
        }
        if (!java.util.Set.of("catch-up-and-serve", "sync-and-exit", "backfill", "force-refresh").contains(mode)) {
            throw new IllegalArgumentException("unsupported ingestion mode");
        }
        if (requestKey == null || requestKey.isBlank()
                || applicationVersion == null || applicationVersion.isBlank()) {
            throw new IllegalArgumentException("requestKey and applicationVersion are required");
        }
    }

    String logicalKey() {
        // Invocation mode, process version and wall clock do not create new logical work.
        return ObservationIdentity.hash(jobDefinitionId.toString(), sp500SnapshotId.toString(),
                nasdaq100SnapshotId.toString(), windowStart.toString(), windowEnd.toString(), requestKey);
    }
}
