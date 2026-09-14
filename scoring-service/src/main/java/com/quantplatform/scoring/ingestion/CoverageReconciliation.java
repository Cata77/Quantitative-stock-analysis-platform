package com.quantplatform.scoring.ingestion;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "scoring.coverage-enabled", havingValue = "true", matchIfMissing = true)
public class CoverageReconciliation {
    private final DurableObservationProcessor processor;
    public CoverageReconciliation(DurableObservationProcessor processor) { this.processor = processor; }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${scoring.coverage-interval-ms:2000}", initialDelayString = "${scoring.coverage-interval-ms:2000}")
    public void reconcile() {
        try { processor.reconcileCoverage(); }
        catch (RuntimeException exception) { LoggerFactory.getLogger(getClass()).warn("Canonical coverage reconciliation will retry: {}", exception.getClass().getSimpleName()); }
    }
}
