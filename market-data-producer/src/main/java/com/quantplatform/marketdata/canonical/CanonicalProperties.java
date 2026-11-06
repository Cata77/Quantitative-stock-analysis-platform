package com.quantplatform.marketdata.canonical;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Canonical processing inside the collector (P2s, stage B). New provider pages are accepted in their
 * staging transaction; the delivery loop only drains events left PENDING (backlog from before the
 * upgrade, explicit dead-letter replays, or instruments waiting for an earlier event).
 */
@ConfigurationProperties("canonical")
public record CanonicalProperties(
        @DefaultValue("true") boolean deliveryEnabled,
        @DefaultValue("1000") long deliveryIntervalMs,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("3") int retryAttempts,
        @DefaultValue("1s") Duration retryBackoff,
        @DefaultValue Command command) {
    public CanonicalProperties {
        if (lease == null || lease.toMillis() < 1 || retryAttempts < 0 || retryBackoff == null
                || retryBackoff.isNegative() || retryBackoff.isZero() || deliveryIntervalMs < 1) {
            throw new IllegalArgumentException("invalid canonical delivery settings");
        }
        if (command == null) command = new Command("serve", null, null, null, null, 20);
    }

    /** Operator commands for the dead-letter quarantine; any value except serve runs once and exits. */
    public record Command(@DefaultValue("serve") String operation, UUID deadLetterId, UUID replayId,
                          String operator, String reason, @DefaultValue("20") int limit) {
        public Command {
            if (operation == null) operation = "serve";
            if (!Set.of("serve", "dlq-inspect", "dlq-replay").contains(operation)) {
                throw new IllegalArgumentException("unknown canonical operation");
            }
        }
        public boolean serve() { return operation.equals("serve"); }
    }
}
