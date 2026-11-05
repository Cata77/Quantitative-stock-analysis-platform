package com.quantplatform.scoring.ingestion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantplatform.scoring.ingestion.event.MarketDataEvent;
import com.quantplatform.scoring.persistence.FundamentalSnapshotEntity;
import com.quantplatform.scoring.persistence.FundamentalSnapshotRepository;
import com.quantplatform.scoring.persistence.MarketBarEntity;
import com.quantplatform.scoring.persistence.MarketBarRepository;

@Service
public class MarketDataEventProcessor {

    private final MarketBarRepository marketBarRepository;
    private final FundamentalSnapshotRepository fundamentalRepository;

    public MarketDataEventProcessor(
            MarketBarRepository marketBarRepository,
            FundamentalSnapshotRepository fundamentalRepository
    ) {
        this.marketBarRepository = marketBarRepository;
        this.fundamentalRepository = fundamentalRepository;
    }

    @Transactional
    public void process(MarketDataEvent event) {
        switch (event.eventType()) {
            case STOCK_BAR -> marketBarRepository.save(MarketBarEntity.from(event));
            case FUNDAMENTAL_SNAPSHOT -> storeFundamentals(event);
        }
    }

    private void storeFundamentals(MarketDataEvent event) {
        // Search reads PostgreSQL since P2s; no Elasticsearch document is maintained.
        fundamentalRepository.save(FundamentalSnapshotEntity.from(event));
    }

}
