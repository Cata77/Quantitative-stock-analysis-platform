package com.quantplatform.marketdata.operations;

import java.time.Instant;
import java.sql.SQLException;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name = "market-data.enabled", havingValue = "true")
public class IngestionRuntime implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(IngestionRuntime.class);
    private final IngestionCoordinator coordinator;
    private final IngestionProperties properties;
    private final ReentrantLock cycleLock = new ReentrantLock();
    private volatile int exitCode = 2;

    public IngestionRuntime(IngestionCoordinator coordinator, IngestionProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        Instant deadline = Instant.now().plus(properties.syncTimeout());
        do {
            cycle();
            var progress = coordinator.status();
            String state = progress.state();
            if (state.equals("READY")) { exitCode = 0; return; }
            // An exhausted item must not abandon unrelated planned sessions.
            // Failures remain visible; a reconciliation exception clears recoverableItems.
            if (state.equals("FAILED") && progress.recoverableItems() == 0) { exitCode = 1; return; }
            LockSupport.parkNanos(500_000_000);
        } while (Instant.now().isBefore(deadline) && !Thread.currentThread().isInterrupted());
    }

    @Scheduled(fixedDelayString = "${ingestion.interval-ms:2000}", initialDelayString = "${ingestion.interval-ms:2000}")
    public void scheduled() {
        if (properties.schedulesEnabled() && properties.mode().equals("catch-up-and-serve")) cycle();
    }

    public void cycle() {
        if (!cycleLock.tryLock()) return;
        try {
            // Delivery to canonical data is done by the scoring-service outbox worker (P2s, no Kafka).
            coordinator.reconcile();
        } catch (RuntimeException failure) {
            // Provider/SQL exception messages may contain credentials or full payloads.
            // Keep enough classification to diagnose a stopped CLI without logging them.
            Throwable root = failure;
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
            while (root.getCause() != null && seen.add(root) && !seen.contains(root.getCause())) {
                root = root.getCause();
            }
            String sqlState = root instanceof SQLException sql ? sql.getSQLState() : null;
            log.error("Ingestion reconciliation stopped: failureType={}, causeType={}, sqlState={}",
                    failure.getClass().getSimpleName(), root.getClass().getSimpleName(), sqlState);
            coordinator.failed("Reconciliation failed: " + failure.getClass().getSimpleName());
        } finally { cycleLock.unlock(); }
    }

    public int exitCode() { return exitCode; }
}
