package com.quantplatform.scoring.ingestion;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantplatform.scoring.calculation.ImpliedVolatilityRankCalculator;
import com.quantplatform.scoring.ingestion.event.MarketDataEvent;
import com.quantplatform.scoring.persistence.FundamentalSnapshotEntity;
import com.quantplatform.scoring.persistence.FundamentalSnapshotRepository;
import com.quantplatform.scoring.persistence.MarketBarEntity;
import com.quantplatform.scoring.persistence.MarketBarRepository;
import com.quantplatform.scoring.persistence.OptionChainEntity;
import com.quantplatform.scoring.persistence.OptionChainRepository;
import com.quantplatform.scoring.search.CompanyMetadataChanged;

@Service
public class MarketDataEventProcessor {

    private static final Duration IV_RANK_WINDOW = Duration.ofDays(364);

    private final MarketBarRepository marketBarRepository;
    private final FundamentalSnapshotRepository fundamentalRepository;
    private final OptionChainRepository optionRepository;
    private final ImpliedVolatilityRankCalculator ivRankCalculator;
    private final ApplicationEventPublisher eventPublisher;

    public MarketDataEventProcessor(
            MarketBarRepository marketBarRepository,
            FundamentalSnapshotRepository fundamentalRepository,
            OptionChainRepository optionRepository,
            ImpliedVolatilityRankCalculator ivRankCalculator,
            ApplicationEventPublisher eventPublisher
    ) {
        this.marketBarRepository = marketBarRepository;
        this.fundamentalRepository = fundamentalRepository;
        this.optionRepository = optionRepository;
        this.ivRankCalculator = ivRankCalculator;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void process(MarketDataEvent event) {
        switch (event.eventType()) {
            case STOCK_BAR -> marketBarRepository.save(MarketBarEntity.from(event));
            case FUNDAMENTAL_SNAPSHOT -> storeFundamentals(event);
            case OPTION_SNAPSHOT -> storeOption(event);
        }
    }

    private void storeFundamentals(MarketDataEvent event) {
        var saved = fundamentalRepository.save(FundamentalSnapshotEntity.from(event));
        eventPublisher.publishEvent(new CompanyMetadataChanged(
                saved.getSymbol(),
                saved.getName(),
                saved.getExchange(),
                saved.getCountry(),
                saved.getSector(),
                saved.getIndustry(),
                saved.getTime()));
    }

    private void storeOption(MarketDataEvent event) {
        var option = event.option();
        var since = option.time().minus(IV_RANK_WINDOW);
        var historicalMinimum = optionRepository.findMinimumImpliedVolatility(
                event.symbol(), since, option.time());
        var historicalMaximum = optionRepository.findMaximumImpliedVolatility(
                event.symbol(), since, option.time());
        var minimum = min(option.impliedVolatility(), historicalMinimum);
        var maximum = max(option.impliedVolatility(), historicalMaximum);
        BigDecimal ivRank = null;
        if (minimum != null && maximum != null && maximum.compareTo(minimum) > 0) {
            ivRank = ivRankCalculator.calculate(
                    option.impliedVolatility(), minimum, maximum);
        }
        optionRepository.save(OptionChainEntity.from(event, ivRank));
    }

    private BigDecimal min(BigDecimal left, BigDecimal right) {
        return right == null || left.compareTo(right) <= 0 ? left : right;
    }

    private BigDecimal max(BigDecimal left, BigDecimal right) {
        return right == null || left.compareTo(right) >= 0 ? left : right;
    }
}
