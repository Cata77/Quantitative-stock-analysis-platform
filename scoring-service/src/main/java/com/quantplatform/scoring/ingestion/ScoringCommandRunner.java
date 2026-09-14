package com.quantplatform.scoring.ingestion;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.scoring.calculation.MonthEndScoringCoordinator;
import com.quantplatform.scoring.config.ScoringCommandProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ScoringCommandRunner implements ApplicationRunner {
    private final ScoringCommandProperties properties;
    private final DeadLetterService deadLetters;
    private final MonthEndScoringCoordinator scores;
    private int exitCode;
    public ScoringCommandRunner(ScoringCommandProperties properties, DeadLetterService deadLetters, MonthEndScoringCoordinator scores) {
        this.properties = properties;
        this.deadLetters = deadLetters;
        this.scores = scores;
    }
    @Override
    public void run(ApplicationArguments args) {
        switch (properties.operation()) {
            case "serve" -> { }
            case "score-and-exit" -> exitCode = scores.calculate(properties.scoreDate()) ? 0 : 2;
            case "dlq-inspect" -> System.out.println(CanonicalJson.MAPPER.writeValueAsString(deadLetters.inspect(properties.limit())));
            case "dlq-replay" -> exitCode = deadLetters.replay(properties.deadLetterId(), properties.replayId(), properties.operator(), properties.reason()) ? 0 : 2;
            default -> throw new IllegalArgumentException("unknown command");
        }
    }
    public int exitCode() { return exitCode; }
}
