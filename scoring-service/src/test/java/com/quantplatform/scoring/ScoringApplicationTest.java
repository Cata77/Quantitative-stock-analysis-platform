package com.quantplatform.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.quantplatform.scoring.calculation.FactorScoringService;
import com.quantplatform.scoring.ingestion.MarketDataEventProcessor;
import com.quantplatform.scoring.ingestion.event.FundamentalSnapshot;
import com.quantplatform.scoring.ingestion.event.MarketDataEvent;
import com.quantplatform.scoring.ingestion.event.MarketDataEventType;
import com.quantplatform.scoring.ingestion.event.OptionSnapshot;
import com.quantplatform.scoring.ingestion.event.OptionType;
import com.quantplatform.scoring.ingestion.event.StockBar;
import com.quantplatform.scoring.persistence.FactorScoreRepository;
import com.quantplatform.scoring.persistence.OptionChainRepository;
import com.quantplatform.scoring.persistence.TemporalSymbolId;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:scoring;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.kafka.listener.auto-startup=false",
                "scoring.elasticsearch.enabled=false"
        })
@Transactional
class ScoringApplicationTest {

    private static final Instant AS_OF = Instant.parse("2026-07-26T00:00:00Z");
    private static final Instant REFERENCE = Instant.parse("2025-07-26T00:00:00Z");
    private static final Instant LATEST = Instant.parse("2026-07-25T20:00:00Z");

    @Autowired
    private MarketDataEventProcessor processor;

    @Autowired
    private FactorScoringService scoringService;

    @Autowired
    private FactorScoreRepository scoreRepository;

    @Autowired
    private OptionChainRepository optionRepository;

    @Test
    void contextLoadsAndPersistsPointInTimeScores() {
        seed("AAA", "10", "0.10", "100", "110");
        seed("BBB", "5", "0.20", "100", "120");
        seed("CCC", "3.33333333", "0.30", "100", "130");

        var scores = scoringService.calculateAt(AS_OF);

        assertThat(scores).hasSize(3);
        assertThat(scoreRepository.count()).isEqualTo(3);
        assertThat(scores).extracting(score -> score.symbol())
                .containsExactly("AAA", "BBB", "CCC");
        assertThat(scores.get(1).zMomentum()).isEqualByComparingTo("0");
    }

    @Test
    void persistsAnOptionWithItsFiftyTwoWeekIvRank() {
        processor.process(option(
                Instant.parse("2026-01-02T15:30:00Z"), "0.10"));
        processor.process(option(
                Instant.parse("2026-02-02T15:30:00Z"), "0.30"));
        var currentTime = Instant.parse("2026-07-25T15:30:00Z");
        processor.process(option(currentTime, "0.25"));

        var saved = optionRepository.findById(
                new TemporalSymbolId(currentTime, "AAPL260821C00200000"));

        assertThat(saved).isPresent();
        assertThat(saved.orElseThrow().getImpliedVolatilityRank())
                .isEqualByComparingTo("75.000000");
    }

    private void seed(
            String symbol,
            String peRatio,
            String returnOnEquity,
            String referenceClose,
            String latestClose
    ) {
        processor.process(fundamentals(symbol, peRatio, returnOnEquity));
        processor.process(bar(symbol, REFERENCE, referenceClose));
        processor.process(bar(symbol, LATEST, latestClose));
    }

    private MarketDataEvent fundamentals(
            String symbol,
            String peRatio,
            String returnOnEquity
    ) {
        var snapshot = new FundamentalSnapshot(
                symbol + " Incorporated",
                "Common Stock",
                "NASDAQ",
                "USD",
                "USA",
                "Technology",
                "Software",
                LocalDate.of(2026, 6, 30),
                new BigDecimal("1000000000"),
                null,
                null,
                new BigDecimal(peRatio),
                null,
                null,
                null,
                null,
                null,
                null,
                new BigDecimal(returnOnEquity),
                null,
                null,
                null,
                null);
        return new MarketDataEvent(
                UUID.randomUUID(),
                1,
                MarketDataEventType.FUNDAMENTAL_SNAPSHOT,
                symbol,
                "alpha-vantage",
                LATEST.minusSeconds(60),
                null,
                snapshot,
                null);
    }

    private MarketDataEvent bar(String symbol, Instant time, String close) {
        var price = new BigDecimal(close);
        var bar = new StockBar(
                time,
                price,
                price,
                price,
                price,
                1000,
                price,
                100);
        return new MarketDataEvent(
                UUID.randomUUID(),
                1,
                MarketDataEventType.STOCK_BAR,
                symbol,
                "alpaca",
                time,
                bar,
                null,
                null);
    }

    private MarketDataEvent option(Instant time, String impliedVolatility) {
        var snapshot = new OptionSnapshot(
                "AAPL260821C00200000",
                time,
                LocalDate.of(2026, 8, 21),
                new BigDecimal("200"),
                OptionType.CALL,
                new BigDecimal("12"),
                new BigDecimal("11.90"),
                new BigDecimal("12.10"),
                100,
                500,
                new BigDecimal(impliedVolatility),
                new BigDecimal("0.55"),
                new BigDecimal("0.02"),
                new BigDecimal("-0.10"),
                new BigDecimal("0.15"),
                new BigDecimal("0.05"));
        return new MarketDataEvent(
                UUID.randomUUID(),
                1,
                MarketDataEventType.OPTION_SNAPSHOT,
                "AAPL",
                "tradier",
                time,
                null,
                null,
                snapshot);
    }
}
