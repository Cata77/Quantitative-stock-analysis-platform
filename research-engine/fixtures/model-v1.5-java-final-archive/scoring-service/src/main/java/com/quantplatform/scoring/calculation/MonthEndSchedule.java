package com.quantplatform.scoring.calculation;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "scoring.month-end-enabled", havingValue = "true", matchIfMissing = true)
public class MonthEndSchedule {
    private final MonthEndScoringCoordinator coordinator;
    public MonthEndSchedule(MonthEndScoringCoordinator coordinator) { this.coordinator = coordinator; }
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${scoring.month-end-interval-ms:60000}", initialDelayString = "${scoring.month-end-interval-ms:60000}")
    public void reconcile() {
        try { coordinator.reconcile(); }
        catch (RuntimeException failure) { LoggerFactory.getLogger(getClass()).warn("Month-end reconciliation will retry: {}", failure.getClass().getSimpleName()); }
    }
}
