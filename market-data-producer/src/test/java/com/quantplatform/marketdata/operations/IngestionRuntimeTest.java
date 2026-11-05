package com.quantplatform.marketdata.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class IngestionRuntimeTest {
    @Test @ExtendWith(OutputCaptureExtension.class)
    void reconciliationLogsDatabaseClassificationWithoutSensitiveExceptionText(CapturedOutput output) {
        var coordinator = mock(IngestionCoordinator.class);
        var cause = new java.sql.SQLException("password=private-fixture-value", "08006");
        when(coordinator.reconcile()).thenThrow(new IllegalStateException("secret provider payload", cause));
        runtime(coordinator).cycle();
        assertThat(output.getAll()).contains("failureType=IllegalStateException", "causeType=SQLException", "sqlState=08006")
                .doesNotContain("private-fixture-value", "secret provider payload");
        verify(coordinator).failed("Reconciliation failed: IllegalStateException");
    }

    @Test void drainsUnrelatedWorkBeforeReturningFailure() {
        var coordinator = mock(IngestionCoordinator.class);
        when(coordinator.status()).thenReturn(status("FAILED", 1), status("FAILED", 0));
        var runtime = runtime(coordinator);
        runtime.run(null);
        verify(coordinator, times(2)).reconcile();
        assertThat(runtime.exitCode()).isEqualTo(1);
    }

    @Test void readyReturnsSuccessAndTerminalFailureStopsImmediately() {
        for (String state : new String[] {"READY", "FAILED"}) {
            var coordinator = mock(IngestionCoordinator.class);
            when(coordinator.status()).thenReturn(status(state, 0));
            var runtime = runtime(coordinator);
            runtime.run(null);
            verify(coordinator).reconcile();
            assertThat(runtime.exitCode()).isEqualTo(state.equals("READY") ? 0 : 1);
        }
    }

    @Test void reconciliationExceptionFailsInsteadOfLoopingOnOldPendingWork() {
        var coordinator = mock(IngestionCoordinator.class);
        when(coordinator.reconcile()).thenThrow(new IllegalStateException("fixture"));
        when(coordinator.status()).thenReturn(status("FAILED", 0));
        var runtime = runtime(coordinator);
        runtime.run(null);
        verify(coordinator).failed("Reconciliation failed: IllegalStateException");
        assertThat(runtime.exitCode()).isEqualTo(1);
    }

    private IngestionCoordinator.Status status(String state, long recoverable) {
        return new IngestionCoordinator.Status(state, "fixture", 1 + recoverable,
                1, null, null, "sip", true, recoverable);
    }

    private IngestionRuntime runtime(IngestionCoordinator coordinator) {
        var properties = new IngestionProperties("sync-and-exit", null, null, null, null,
                false, 1000, 5, Duration.ofMinutes(2), Duration.ofSeconds(5),
                Duration.ofSeconds(10), "https://paper-api.alpaca.markets", "scoring-service-v2");
        return new IngestionRuntime(coordinator, properties);
    }
}
