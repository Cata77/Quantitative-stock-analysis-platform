package com.quantplatform.scoring.ingestion;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.scoring.config.ScoringCommandProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Operator commands of the ingestion consumer. Scores are published by the Python research engine. */
@Component
public class ScoringCommandRunner implements ApplicationRunner {
    private final ScoringCommandProperties properties;
    private final DeadLetterService deadLetters;
    private int exitCode;
    public ScoringCommandRunner(ScoringCommandProperties properties, DeadLetterService deadLetters) {
        this.properties = properties;
        this.deadLetters = deadLetters;
    }
    @Override
    public void run(ApplicationArguments args) {
        switch (properties.operation()) {
            case "serve" -> { }
            case "dlq-inspect" -> System.out.println(CanonicalJson.MAPPER.writeValueAsString(deadLetters.inspect(properties.limit())));
            case "dlq-replay" -> exitCode = deadLetters.replay(properties.deadLetterId(), properties.replayId(), properties.operator(), properties.reason()) ? 0 : 2;
            default -> throw new IllegalArgumentException("unknown command");
        }
    }
    public int exitCode() { return exitCode; }
}
