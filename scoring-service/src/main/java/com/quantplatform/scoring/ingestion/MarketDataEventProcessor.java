package com.quantplatform.scoring.ingestion;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantplatform.scoring.ingestion.event.MarketDataEvent;
import com.quantplatform.scoring.persistence.FundamentalSnapshotEntity;
import com.quantplatform.scoring.persistence.FundamentalSnapshotRepository;
import com.quantplatform.scoring.persistence.MarketBarEntity;
import com.quantplatform.scoring.persistence.MarketBarRepository;
import com.quantplatform.scoring.search.CompanyMetadataChanged;

@Service
public class MarketDataEventProcessor {

    private final MarketBarRepository marketBarRepository;
    private final FundamentalSnapshotRepository fundamentalRepository;
    private final ApplicationEventPublisher eventPublisher;

    public MarketDataEventProcessor(
            MarketBarRepository marketBarRepository,
            FundamentalSnapshotRepository fundamentalRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.marketBarRepository = marketBarRepository;
        this.fundamentalRepository = fundamentalRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void process(MarketDataEvent event) {
        switch (event.eventType()) {
            case STOCK_BAR -> marketBarRepository.save(MarketBarEntity.from(event));
            case FUNDAMENTAL_SNAPSHOT -> storeFundamentals(event);
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

}
