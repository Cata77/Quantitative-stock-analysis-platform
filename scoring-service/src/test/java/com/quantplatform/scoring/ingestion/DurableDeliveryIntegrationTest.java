package com.quantplatform.scoring.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.ingestion.ObservationEvent;
import com.quantplatform.marketdata.provider.alpaca.AlpacaStockMarketClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;

/** Delivery of the outbox directly from PostgreSQL (P2s): the guarantees the Kafka transport gave. */
@Testcontainers
class DurableDeliveryIntegrationTest extends DurableDeliveryFixture {
    void expireLeases() { jdbc.sql("UPDATE operations.outbox_events SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE status='CLAIMED'").update(); }
    void retryNow() { jdbc.sql("UPDATE operations.outbox_events SET next_retry_at=clock_timestamp()-INTERVAL '1 second' WHERE status='FAILED_RETRYABLE'").update(); }

    @Test void committedOutboxIsDeliveredOnceAndCompletesCoverage() {
        stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        assertThat(count("market_data.observations")).isZero();
        assertThat(worker.drain(10)).isEqualTo(1);
        assertThat(worker.drain(10)).isZero();
        assertThat(count("operations.kafka_inbox")).isEqualTo(1);
        assertThat(count("market_data.observations")).isEqualTo(1);
        assertThat(text("SELECT status FROM operations.outbox_events")).isEqualTo("PUBLISHED");
        verify(projection, times(1)).process(any());
        processor.reconcileCoverage();
        assertThat(text("SELECT status FROM operations.ingestion_runs")).isEqualTo("COMPLETE");
    }

    @Test void crashAfterCanonicalCommitRedeliversAfterLeaseExpiryWithoutAnotherWrite() {
        var event = stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        var abandoned = worker.claim().orElseThrow();
        assertThat(processor.process(event, abandoned.topic(), 0, abandoned.offset())).isTrue(); // then the worker dies
        assertThat(worker.claim()).as("an unexpired lease is never shared").isEmpty();
        expireLeases();
        assertThat(worker.drain(10)).isZero();
        assertThat(text("SELECT status FROM operations.outbox_events")).isEqualTo("PUBLISHED");
        assertThat(scalar("SELECT attempt_count FROM operations.outbox_events")).isEqualTo(2);
        assertThat(scalar("SELECT delivery_count FROM operations.kafka_inbox")).isEqualTo(2);
        assertThat(count("market_data.observations")).isEqualTo(1);
        verify(projection, times(1)).process(any());
    }

    @Test void concurrentWorkersCannotShareClaims() {
        stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        var other = new OutboxDeliveryWorker(source, tx, processor, deadLetters, properties, Duration.ofMinutes(2));
        var claim = worker.claim().orElseThrow();
        assertThat(other.claim()).isEmpty();
        expireLeases();
        var stolen = other.claim().orElseThrow();
        assertThat(stolen.eventId()).isEqualTo(claim.eventId());
        assertThat(stolen.token()).isNotEqualTo(claim.token());
        assertThat(stolen.attempt()).isEqualTo(2);
    }

    @Test void eventsOfOneInstrumentAreDeliveredInCreationOrder() {
        stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        stage(LocalDate.parse("2026-09-02"), "scheduled", "100");
        doThrow(new IllegalStateException("projection unavailable")).doNothing().when(projection).process(any());
        assertThat(worker.drain(1)).isZero();
        jdbc.sql("UPDATE operations.outbox_events SET next_retry_at=clock_timestamp()+INTERVAL '1 hour' WHERE status='FAILED_RETRYABLE'").update();
        assertThat(jdbc.sql("SELECT status FROM operations.outbox_events ORDER BY created_at,event_id").query(String.class).list())
                .containsExactly("FAILED_RETRYABLE", "PENDING");
        assertThat(worker.claim()).as("a later event waits for the earlier one of the same instrument").isEmpty();
        retryNow();
        assertThat(worker.drain(10)).isEqualTo(2);
        assertThat(count("market_data.observations")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT DISTINCT status FROM operations.outbox_events").query(String.class).list())
                .containsExactly("PUBLISHED");
    }

    @Test void transientFailuresAreBoundedThenDeadLetteredAndReplayIsExplicitAndAudited() {
        stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        doThrow(new IllegalStateException("dependency down")).when(projection).process(any());
        for (int attempt = 1; attempt <= properties.retryAttempts() + 1; attempt++) {
            worker.drain(1);
            assertThat(scalar("SELECT attempt_count FROM operations.outbox_events")).isEqualTo(attempt);
            retryNow();
        }
        assertThat(text("SELECT status FROM operations.outbox_events")).isEqualTo("FAILED_TERMINAL");
        assertThat(count("operations.dead_letter_records")).isEqualTo(1);
        assertThat(count("operations.kafka_inbox")).isZero();
        assertThat(worker.drain(10)).isZero();
        var entry = deadLetters.inspect(10).getFirst();
        assertThat(entry.reason()).contains("dependency down");
        UUID request = UUID.randomUUID();
        assertThat(deadLetters.replay(entry.id(), request, "test-operator", "dependency restored")).isTrue();
        assertThat(deadLetters.replay(entry.id(), request, "test-operator", "dependency restored")).isTrue();
        assertThatThrownBy(() -> deadLetters.replay(entry.id(), request, "another-operator", "changed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("operations.dead_letter_replays")).isEqualTo(1);
        assertThat(text("SELECT status FROM operations.dead_letter_replays")).isEqualTo("PUBLISHED");
        reset(projection);
        assertThat(worker.drain(10)).isEqualTo(1);
        assertThat(count("market_data.observations")).isEqualTo(1);
        assertThat(text("SELECT status FROM operations.outbox_events")).isEqualTo("PUBLISHED");
    }

    @Test void invalidPricesAndPayloadHashesAreQuarantinedWithoutCanonicalWrites() {
        var negative = stage(LocalDate.parse("2026-09-01"), "bad-price", "-1");
        var tampered = stage(LocalDate.parse("2026-09-02"), "wrong-hash", "100");
        jdbc.sql("UPDATE operations.outbox_events SET payload_hash=repeat('0',64) WHERE event_id=:id")
                .param("id", tampered.eventId()).update();
        assertThat(worker.drain(10)).isZero();
        assertThat(count("operations.dead_letter_records")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT DISTINCT status FROM operations.outbox_events").query(String.class).list())
                .containsExactly("FAILED_TERMINAL");
        var entries = deadLetters.inspect(10);
        assertThat(entries).extracting(DeadLetterService.Inspection::topic).containsOnly(topic);
        String negativeJson = jdbc.sql("SELECT payload::text FROM operations.outbox_events WHERE event_id=:id")
                .param("id", negative.eventId()).query(String.class).single();
        assertThat(entries).anySatisfy(e -> assertThat(new String(Base64.getMimeDecoder().decode(e.payloadBase64())))
                .isEqualTo(negativeJson));
        assertThat(count("operations.kafka_inbox")).isZero();
        assertThat(count("market_data.observations")).isZero();
        processor.reconcileCoverage();
        assertThat(count("operations.data_coverage")).isZero();
    }

    @Test void failedCanonicalTransactionRollsBackInboxAndCanBeRetried() {
        var event = stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        doThrow(new IllegalStateException("projection write failed")).doNothing().when(projection).process(any());
        assertThatThrownBy(() -> processor.process(event, topic, 0, 0)).isInstanceOf(IllegalStateException.class);
        assertThat(count("operations.kafka_inbox")).isZero();
        assertThat(count("operations.kafka_event_receipts")).isZero();
        assertThat(count("market_data.observations")).isZero();
        processor.reconcileCoverage();
        assertThat(count("operations.data_coverage")).isZero();
        assertThat(processor.process(event, topic, 0, 0)).isTrue();
        processor.reconcileCoverage();
        assertThat(count("operations.data_coverage")).isEqualTo(1);
    }

    @Test void laterProviderRunReusesObservationButCorrectionsAppend() {
        LocalDate date = LocalDate.parse("2026-09-01");
        var first = stage(date, "scheduled", "100.00");
        var repeated = stage(date, "force:unchanged", "100");
        assertThat(repeated.eventId()).isEqualTo(first.eventId());
        assertThat(count("operations.outbox_events")).isEqualTo(1);
        processor.process(first, topic, 0, 0);
        var physicalRepeat = new ObservationEvent(UUID.randomUUID(), 2, first.observationKey(), dataset,
                instrument, first.eventType(), first.economicTime(), first.adjustmentMode(), first.payload());
        assertThat(processor.process(physicalRepeat, topic, 0, 1)).isFalse();
        var correction = stage(date, "force:correction", "101");
        assertThat(processor.process(correction, topic, 0, 2)).isTrue();
        assertThat(count("market_data.observations")).isEqualTo(2);
        assertThat(count("operations.kafka_event_receipts")).isEqualTo(3);
        var collision = new ObservationEvent(physicalRepeat.eventId(), 2, correction.observationKey(), dataset,
                instrument, correction.eventType(), correction.economicTime(), correction.adjustmentMode(), correction.payload());
        assertThatThrownBy(() -> processor.process(collision, topic, 0, 3)).isInstanceOf(MarketDataValidationException.class);
        assertThat(count("operations.kafka_inbox")).isEqualTo(2);
    }

    @Test void contiguousWatermarkStopsAtMissingSessionAndRetractsInvalidCoverage() {
        calendar(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-03"));
        var first = stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        var last = stage(LocalDate.parse("2026-09-03"), "scheduled", "100");
        processor.process(first, topic, 0, 0);
        processor.process(last, topic, 0, 1);
        processor.reconcileCoverage();
        assertThat(text("SELECT complete_through::text FROM operations.data_watermarks")).isEqualTo("2026-09-01");
        var middle = stage(LocalDate.parse("2026-09-02"), "scheduled", "100");
        processor.process(middle, topic, 0, 2);
        processor.reconcileCoverage();
        assertThat(text("SELECT complete_through::text FROM operations.data_watermarks")).isEqualTo("2026-09-03");
        jdbc.sql("UPDATE operations.data_coverage SET valid=false WHERE boundary_date='2026-09-02'").update();
        processor.reconcileCoverage();
        assertThat(text("SELECT complete_through::text FROM operations.data_watermarks")).isEqualTo("2026-09-01");
        jdbc.sql("UPDATE operations.data_coverage SET valid=false").update();
        processor.reconcileCoverage();
        assertThat(count("operations.data_watermarks")).isZero();
    }

    @Test void startupAfterAWeekOnlyRequestsMissingSessionsAndRetriesPartialFailure() {
        calendar(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-09-12"));
        var alpaca = mock(AlpacaStockMarketClient.class);
        var dates = new ArrayList<LocalDate>();
        var failOnce = new AtomicInteger();
        when(alpaca.fetchDurablePage(anyString(), any(), any(), anyString())).thenAnswer(call -> {
            LocalDate date = LocalDate.ofInstant(call.getArgument(1), ZoneId.of("America/New_York"));
            dates.add(date);
            if (date.equals(LocalDate.parse("2026-09-08")) && failOnce.incrementAndGet() == 1) throw new IllegalStateException("provider unavailable");
            var bar = CanonicalJson.MAPPER.readValue(payload(date, "100"), com.quantplatform.marketdata.event.StockBar.class);
            return new AlpacaStockMarketClient.DurableBarPage(List.of(bar), null, "{\"bars\":[]}", "test://bars/" + date,
                    date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        });
        var before = coordinator(alpaca, "2026-09-05T12:00:00Z");
        before.reconcile();
        acceptStaged();
        assertThat(before.reconcile().state()).isEqualTo("READY");
        int alreadyRequested = dates.size();
        var after = coordinator(alpaca, "2026-09-12T12:00:00Z");
        assertThat(after.reconcile().state()).isEqualTo("DEGRADED");
        assertThat(dates.subList(alreadyRequested, dates.size())).containsExactly(
                LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-08"), LocalDate.parse("2026-09-09"),
                LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-11"));
        jdbc.sql("UPDATE operations.ingestion_run_items SET next_retry_at=clock_timestamp()-INTERVAL '1 second' WHERE status='WAITING_RETRY'").update();
        after.reconcile();
        acceptStaged();
        assertThat(after.reconcile().state()).isEqualTo("READY");
        assertThat(dates.stream().filter(LocalDate.parse("2026-09-08")::equals).count()).isEqualTo(2);
        int completeRequests = dates.size();
        coordinator(alpaca, "2026-09-12T15:00:00Z").reconcile();
        assertThat(dates).hasSize(completeRequests);
    }

    @Test void concurrentVirtualThreadDeliveriesHaveOneEffectWithoutCarrierPinning(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path recordingDirectory) throws Exception {
        var event = stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        // Warm sockets, Jackson and both ingestion branches independently of test execution order.
        // Roll back warm-up effects before measuring steady-state database contention.
        try (var warmup = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            warmup.submit(() -> {
                jdbc.sql("SELECT 1 FROM pg_sleep(0.01)").query(Integer.class).single();
                new org.springframework.transaction.support.TransactionTemplate(tx).executeWithoutResult(status -> {
                    assertThat(processor.process(event, topic, 0, 0)).isTrue();
                    assertThat(processor.process(event, topic, 0, 0)).isFalse();
                    status.setRollbackOnly();
                });
            }).get(10, TimeUnit.SECONDS);
            clearInvocations(projection);
            assertThat(count("operations.kafka_inbox")).isZero();
            assertThat(count("market_data.observations")).isZero();
        }
        var recordingFile = recordingDirectory.resolve("phase3-ingestion.jfr");
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO);
            recording.start();
            try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = java.util.stream.IntStream.range(0, 16)
                        .<java.util.concurrent.Callable<Boolean>>mapToObj(offset -> () -> processor.process(event, topic, 0, offset)).toList();
                var results = workers.invokeAll(tasks, 30, TimeUnit.SECONDS);
                int accepted = 0;
                for (var result : results) if (result.get()) accepted++;
                assertThat(accepted).isEqualTo(1);
            }
            recording.stop();
            recording.dump(recordingFile);
        }
        var evidenceFile = java.nio.file.Path.of("build/reports/phase11-ingestion.jfr");
        java.nio.file.Files.createDirectories(evidenceFile.getParent());
        java.nio.file.Files.copy(recordingFile, evidenceFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThat(jdk.jfr.consumer.RecordingFile.readAllEvents(recordingFile))
                .noneMatch(eventRecord -> eventRecord.getEventType().getName().equals("jdk.VirtualThreadPinned"));
        assertThat(scalar("SELECT delivery_count FROM operations.kafka_inbox")).isEqualTo(16);
        assertThat(count("market_data.observations")).isEqualTo(1);
        verify(projection, times(1)).process(any());
    }

    @Test void aNewBlockingIssueWithdrawsPreviouslyCompleteCoverage() {
        calendar(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-01"));
        var event = stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        processor.process(event, topic, 0, 0);
        processor.reconcileCoverage();
        assertThat(count("operations.data_watermarks")).isEqualTo(1);
        jdbc.sql("""
                INSERT INTO operations.data_quality_issues (dataset_id,instrument_id,severity,issue_type,affected_key,evidence)
                VALUES (:dataset,:instrument,'BLOCKING','CORRECTION_REQUIRED',:key,'{}')
                """).param("dataset", dataset).param("instrument", instrument).param("key", event.observationKey()).update();
        processor.reconcileCoverage();
        assertThat(scalar("SELECT COUNT(*) FROM operations.data_coverage WHERE valid")).isZero();
        assertThat(count("operations.data_watermarks")).isZero();
        jdbc.sql("UPDATE operations.data_quality_issues SET status='RESOLVED',resolved_at=clock_timestamp()").update();
        var correction = stage(LocalDate.parse("2026-09-01"), "force:reviewed-correction", "101");
        processor.process(correction, topic, 0, 1);
        processor.reconcileCoverage();
        assertThat(scalar("SELECT COUNT(*) FROM operations.data_coverage WHERE valid")).isEqualTo(1);
        assertThat(count("operations.data_watermarks")).isEqualTo(1);
    }
}
