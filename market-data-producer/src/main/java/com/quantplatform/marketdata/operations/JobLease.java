package com.quantplatform.marketdata.operations;

import java.time.Instant;
import java.util.UUID;

public record JobLease(UUID itemId, UUID runId, UUID instrumentId, UUID token,
                       int attemptNumber, Instant expiresAt, String checkpointJson) {
}
