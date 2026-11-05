package com.quantplatform.scoring.config;

import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Score publication moved to quant_research.publication (P2s); only consumer operations remain. */
@ConfigurationProperties("scoring.command")
public record ScoringCommandProperties(@DefaultValue("serve") String operation,
        UUID deadLetterId, UUID replayId, String operator, String reason, @DefaultValue("20") int limit) {
    public ScoringCommandProperties {
        if (!Set.of("serve", "dlq-inspect", "dlq-replay").contains(operation)) {
            throw new IllegalArgumentException("unknown scoring operation");
        }
    }
}
