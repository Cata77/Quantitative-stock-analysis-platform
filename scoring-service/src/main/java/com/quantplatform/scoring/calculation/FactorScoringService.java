package com.quantplatform.scoring.calculation;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantplatform.scoring.config.ScoringProperties;
import com.quantplatform.scoring.persistence.FactorScoreEntity;
import com.quantplatform.scoring.persistence.FactorScoreRepository;

@Service
public class FactorScoringService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FactorScoringService.class);

    private final Clock clock;
    private final ScoringProperties properties;
    private final FactorInputAssembler inputAssembler;
    private final CrossSectionalScoringCalculator calculator;
    private final FactorScoreRepository scoreRepository;

    public FactorScoringService(
            Clock clock,
            ScoringProperties properties,
            FactorInputAssembler inputAssembler,
            CrossSectionalScoringCalculator calculator,
            FactorScoreRepository scoreRepository
    ) {
        this.clock = clock;
        this.properties = properties;
        this.inputAssembler = inputAssembler;
        this.calculator = calculator;
        this.scoreRepository = scoreRepository;
    }

    @Scheduled(
            cron = "${scoring.schedule-cron}",
            zone = "${scoring.schedule-zone}")
    @Transactional
    public void calculateDailyScores() {
        calculateAt(clock.instant());
    }

    public List<FactorScore> calculateAt(Instant asOf) {
        var scoringTime = asOf.truncatedTo(ChronoUnit.SECONDS);
        var universe = inputAssembler.assemble(scoringTime);
        if (universe.size() < properties.minimumUniverseSize()) {
            LOGGER.info(
                    "Skipped daily score calculation at {}: eligible universe {} is below {}",
                    scoringTime,
                    universe.size(),
                    properties.minimumUniverseSize());
            return List.of();
        }

        var scores = calculator.calculate(universe);
        scoreRepository.saveAll(scores.stream()
                .map(score -> FactorScoreEntity.from(scoringTime, score))
                .toList());
        LOGGER.info("Persisted {} daily factor scores at {}", scores.size(), scoringTime);
        return scores;
    }
}
