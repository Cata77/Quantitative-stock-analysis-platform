package com.quantplatform.scoring.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.ingestion.ObservationEvent;
import com.quantplatform.marketdata.config.AlpacaProperties;
import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.operations.*;
import com.quantplatform.marketdata.provider.alpaca.AlpacaStockMarketClient;
import com.quantplatform.marketdata.provider.alphavantage.AlphaVantageFundamentalClient;
import com.quantplatform.scoring.calculation.*;
import com.quantplatform.scoring.config.ScoringProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DurableDeliveryIntegrationTest extends DurableDeliveryFixture {
    @Test void committedOutboxSurvivesProducerRestartAndDuplicateDeliveryHasOneBusinessEffect() throws Exception {
        var event = stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        assertThat(count("market_data.observations")).isZero();
        assertThat(new OutboxRelay(new OutboxStore(source, tx, delivery), producer, delivery).drain()).isEqualTo(1);
        send(event);
        startListener(new DurableMarketDataListener(processor)::consume);
        await(() -> count("operations.kafka_inbox") == 1 && scalar("SELECT delivery_count FROM operations.kafka_inbox") == 2);
        assertThat(count("market_data.observations")).isEqualTo(1);
        verify(projection, times(1)).process(any());
        await(() -> committedOffset() == 2);
        processor.reconcileCoverage();
        assertThat(text("SELECT status FROM operations.ingestion_runs")).isEqualTo("COMPLETE");
    }

    @Test void sendBeforeOutboxAcknowledgementCanBeRepeatedAfterLeaseExpiry() throws Exception {
        stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        var abandoned = outbox.claim("crashed-worker").orElseThrow();
        var record = new ProducerRecord<>(abandoned.topic(), abandoned.key(), abandoned.json());
        record.headers().add(ObservationEvent.HASH_HEADER, abandoned.hash().getBytes(StandardCharsets.UTF_8));
        producer.send(record).get(20, TimeUnit.SECONDS);
        jdbc.sql("UPDATE operations.outbox_events SET lease_until=clock_timestamp()-INTERVAL '1 second'").update();
        assertThat(relay.drain()).isEqualTo(1);
        assertThat(outbox.acknowledge(abandoned, 0, 0)).isFalse();
        startListener(new DurableMarketDataListener(processor)::consume);
        await(() -> count("operations.kafka_inbox") == 1 && scalar("SELECT delivery_count FROM operations.kafka_inbox") == 2);
        assertThat(count("market_data.observations")).isEqualTo(1);
        assertThat(scalar("SELECT attempt_count FROM operations.outbox_events")).isEqualTo(2);
        verify(projection, times(1)).process(any());
    }

    @Test void canonicalCommitBeforeOffsetAcknowledgementRedeliversWithoutAnotherWrite() throws Exception {
        stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        relay.drain();
        var listener = new DurableMarketDataListener(processor);
        var calls = new AtomicInteger();
        startListener(record -> {
            listener.consume(record);
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("simulated crash after database commit");
        });
        await(() -> calls.get() == 2 && committedOffset() == 1);
        assertThat(count("market_data.observations")).isEqualTo(1);
        assertThat(scalar("SELECT delivery_count FROM operations.kafka_inbox")).isEqualTo(2);
        verify(projection, times(1)).process(any());
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

    @Test void poisonMessagesPreserveBytesHeadersAndLocationAndReplayIsExplicitAndAudited() throws Exception {
        byte[] poison = new byte[] {(byte) 0xff, 0, 42};
        var record = new ProducerRecord<String, byte[]>(topic, instrument.toString(), poison);
        record.headers().add("fixture", new byte[] {1, 2}).add("fixture", null);
        bytesProducer.send(record).get(20, TimeUnit.SECONDS);
        startListener(new DurableMarketDataListener(processor)::consume);
        await(() -> count("operations.dead_letter_records") == 1 && committedOffset() == 1);
        var entry = deadLetters.inspect(10).getFirst();
        assertThat(Base64.getDecoder().decode(entry.payloadBase64())).isEqualTo(poison);
        assertThat(entry.headers()).contains("fixture", "AQI=", "null");
        assertThat(entry.topic()).isEqualTo(topic);
        assertThat(entry.offset()).isZero();
        try (var dlq = consumer(UUID.randomUUID().toString(), topic + "-dlq")) {
            var records = poll(dlq, 1);
            assertThat(records.getFirst().value()).isEqualTo(poison);
            assertThat(new String(records.getFirst().headers().lastHeader("x-original-offset").value(), StandardCharsets.UTF_8)).isEqualTo("0");
        }
        UUID request = UUID.randomUUID();
        assertThat(deadLetters.replay(entry.id(), request, "test-operator", "verify parser recovery")).isTrue();
        assertThat(deadLetters.replay(entry.id(), request, "test-operator", "verify parser recovery")).isTrue();
        await(() -> count("operations.dead_letter_records") == 2 && committedOffset() == 2);
        assertThat(count("operations.dead_letter_replays")).isEqualTo(1);
        assertThat(text("SELECT status FROM operations.dead_letter_replays")).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> deadLetters.replay(entry.id(), request, "another-operator", "changed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("market_data.observations")).isZero();
    }

    @Test void replayAfterTransientConsumerFailureAcceptsTheOriginalObservation() throws Exception {
        var event = stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        var record = new ConsumerRecord<String, byte[]>(topic, 0, 42, instrument.toString(), event.json().getBytes(StandardCharsets.UTF_8));
        record.headers().add(ObservationEvent.HASH_HEADER, event.payloadHash().getBytes(StandardCharsets.UTF_8));
        deadLetters.recover(record, new IllegalStateException("temporary dependency failure"));
        var id = deadLetters.inspect(1).getFirst().id();
        UUID request = UUID.randomUUID();
        deadLetters.replay(id, request, "operator", "dependency restored");
        startListener(new DurableMarketDataListener(processor)::consume);
        await(() -> count("market_data.observations") == 1 && committedOffset() == 1);
        assertThat(count("operations.dead_letter_records")).isEqualTo(1);
    }

    @Test void outboxRetriesAreBoundedAndConcurrentWorkersCannotShareClaims() {
        stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        for (int attempt = 1; attempt <= 3; attempt++) {
            var claim = outbox.claim("worker").orElseThrow();
            assertThat(new OutboxStore(source, tx, delivery).claim("other-worker")).isEmpty();
            assertThat(claim.attempt()).isEqualTo(attempt);
            outbox.failed(claim, "BROKER_UNAVAILABLE");
            if (attempt < 3) jdbc.sql("UPDATE operations.outbox_events SET next_retry_at=clock_timestamp()-INTERVAL '1 second'").update();
        }
        assertThat(outbox.claim("restart")).isEmpty();
        assertThat(text("SELECT status FROM operations.outbox_events")).isEqualTo("FAILED_TERMINAL");
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

    @Test void completedMonthEndIsNotRecreatedAtANewStartupTimestamp() {
        calendar(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        var event = stage(LocalDate.parse("2026-08-31"), "scheduled", "100");
        processor.process(event, topic, 0, 0);
        processor.reconcileCoverage();
        var calculator = mock(FactorScoringService.class);
        when(calculator.calculateAt(any(), anyList())).thenReturn(List.of(
                new FactorScore("FIX", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
        var first = new MonthEndScoringCoordinator(source, tx, calculator, clock("2026-09-01T12:00:00Z"));
        first.reconcile();
        var restarted = new MonthEndScoringCoordinator(source, tx, calculator, clock("2026-09-14T12:00:00Z"));
        restarted.reconcile();
        assertThat(count("operations.month_end_score_jobs")).isEqualTo(1);
        assertThat(text("SELECT status FROM operations.month_end_score_jobs")).isEqualTo("PUBLISHED");
        verify(calculator, times(1)).calculateAt(Instant.parse("2026-08-31T20:00:00Z"), List.of("FIX"));
    }


    @Test void invalidPricesAndEnvelopeHashesAreQuarantinedWithoutCanonicalWrites() throws Exception {
        var negative = stage(LocalDate.parse("2026-09-01"), "bad-price", "-1");
        send(negative);
        var valid = stage(LocalDate.parse("2026-09-02"), "wrong-hash", "100");
        var tampered = new ProducerRecord<>(topic, instrument.toString(), valid.json());
        tampered.headers().add(ObservationEvent.HASH_HEADER, "wrong".getBytes(StandardCharsets.UTF_8));
        producer.send(tampered).get(20, TimeUnit.SECONDS);
        producer.send(topic, instrument.toString(), "{invalid-json").get(20, TimeUnit.SECONDS);
        startListener(new DurableMarketDataListener(processor)::consume);
        await(() -> count("operations.dead_letter_records") == 3 && committedOffset() == 3);
        assertThat(count("operations.kafka_inbox")).isZero();
        assertThat(count("market_data.observations")).isZero();
        processor.reconcileCoverage();
        assertThat(count("operations.data_coverage")).isZero();
    }

    @Test void concurrentVirtualThreadDeliveriesHaveOneEffectWithoutCarrierPinning(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path recordingDirectory) throws Exception {
        var event = stage(LocalDate.parse("2026-09-01"), "scheduled", "100");
        // Initialize the JDK socket poller before measuring steady-state database contention.
        // Cold startup on Windows records class-initialization pinning, not application monitor I/O.
        try (var warmup = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            warmup.submit(() -> jdbc.sql("SELECT 1 FROM pg_sleep(0.01)").query(Integer.class).single())
                    .get(10, TimeUnit.SECONDS);
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
