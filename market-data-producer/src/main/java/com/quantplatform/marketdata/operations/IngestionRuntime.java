package com.quantplatform.marketdata.operations;

import java.time.Instant;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "market-data.enabled", havingValue = "true")
public class IngestionRuntime implements ApplicationRunner {
    private final IngestionCoordinator coordinator;
    private final OutboxRelay relay;
    private final IngestionProperties properties;
    private final ReentrantLock cycleLock = new ReentrantLock();
    private volatile int exitCode = 2;

    public IngestionRuntime(IngestionCoordinator coordinator, OutboxRelay relay, IngestionProperties properties) {
        this.coordinator = coordinator;
        this.relay = relay;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        Instant deadline = Instant.now().plus(properties.syncTimeout());
        do {
            cycle();
            String state = coordinator.status().state();
            if (state.equals("READY")) { exitCode = 0; return; }
            if (state.equals("FAILED")) { exitCode = 1; return; }
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
            relay.drain();
            coordinator.reconcile();
            relay.drain();
        } catch (RuntimeException failure) {
            coordinator.failed("Reconciliation failed: " + failure.getClass().getSimpleName());
        } finally { cycleLock.unlock(); }
    }

    public int exitCode() { return exitCode; }
}
