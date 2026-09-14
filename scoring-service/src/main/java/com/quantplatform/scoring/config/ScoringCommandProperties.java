package com.quantplatform.scoring.config;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("scoring.command")
public record ScoringCommandProperties(@DefaultValue("serve") String operation, LocalDate scoreDate,
        UUID deadLetterId, UUID replayId, String operator, String reason, @DefaultValue("20") int limit) {
    public ScoringCommandProperties {
        if (!Set.of("serve", "score-and-exit", "dlq-inspect", "dlq-replay").contains(operation)) {
            throw new IllegalArgumentException("unknown scoring operation");
        }
    }
}
