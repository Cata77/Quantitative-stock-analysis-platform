package com.quantplatform.marketdata.canonical;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.marketdata.operations.*;
import com.quantplatform.marketdata.provider.alpaca.AlpacaDailyDataClient;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * P2s stage B: canonical processing runs inside the collector, in the staging transaction of each
 * provider page. No hand-off between processes remains; the inbox, checkpoints, audited retries and
 * the dead-letter quarantine keep their guarantees.
 */
class StagedAcceptanceIntegrationTest extends DurableDeliveryFixture {
    static final LocalDate DAY = LocalDate.parse("2026-09-01");

    @Test void stagedPageIsCanonicalInTheSameTransactionWithoutADeliveryLoop() {
        calendar(DAY, DAY);
        var event = stageWith(collector, DAY, "scheduled", "100");
        assertThat(text("SELECT status FROM operations.outbox_events")).isEqualTo("PUBLISHED");
        assertThat(count("operations.kafka_inbox")).isEqualTo(1);
        assertThat(count("market_data.observations")).isEqualTo(1);
        assertThat(text("SELECT observation_key FROM market_data.observations")).isEqualTo(event.observationKey());
        verify(projection, times(1)).bar(eq("FIX"), eq("fixture"), any());
        assertThat(worker.drain(10)).as("nothing is left for the delivery loop").isZero();
        processor.reconcileCoverage();
        assertThat(text("SELECT status FROM operations.ingestion_runs")).isEqualTo("COMPLETE");
        assertThat(count("operations.data_watermarks")).isEqualTo(1);
    }

    @Test void everyObservationOfAMultiEventPageIsAcceptedInTheStagingTransaction() {
        // One instrument, several events in one page (as with SEC filings): all share created_at.
        var run = collector.plan(new IngestionPlan(job, sp500, nasdaq, DAY, DAY, "backfill", "page", "test"));
        var lease = collector.claim(run, "fixture", 1, Duration.ofMinutes(2)).getFirst();
        var page = new ArrayList<OutboxObservation>();
        for (int day = 0; day < 6; day++) {
            var date = DAY.plusDays(day);
            page.add(new OutboxObservation(topic, "STOCK_BAR", date.atTime(4, 0).toInstant(ZoneOffset.UTC), "RAW",
                    payload(date, "10" + day)));
        }
        collector.stage(lease, new SourceArtifact("page", "test://page", Instant.now(), "fixture", "{}"), page, "{}", true);
        assertThat(jdbc.sql("SELECT DISTINCT status FROM operations.outbox_events").query(String.class).list())
                .containsExactly("PUBLISHED");
        assertThat(count("market_data.observations")).isEqualTo(6);
        assertThat(count("operations.kafka_inbox")).isEqualTo(6);
        assertThat(worker.drain(10)).isZero();
    }

    @Test void restagingAnAcceptedObservationHasNoSecondEffect() {
        stageWith(collector, DAY, "scheduled", "100.00");
        var repeated = stageWith(collector, DAY, "force:unchanged", "100");
        assertThat(count("operations.outbox_events")).isEqualTo(1);
        assertThat(count("market_data.observations")).isEqualTo(1);
        assertThat(processor.process(repeated, topic, 0, 99)).isFalse();
        verify(projection, times(1)).bar(any(), any(), any());
        stageWith(collector, DAY, "force:correction", "101");
        assertThat(count("market_data.observations")).as("a correction appends").isEqualTo(2);
    }

    @Test void invalidObservationIsQuarantinedWhileTheRestOfThePageCommits() {
        calendar(DAY, DAY);
        dailyJob();
        UUID second = secondInstrument();
        var run = plan("mixed");
        var client = mock(AlpacaDailyDataClient.class);
        when(client.bars(anyList(), eq(DAY), eq("RAW"), isNull(), eq("")))
                .thenReturn(page(Map.of("FIX", List.of(price("100", "USD")), "SECOND", List.of(price("100", "EUR"))), ""));
        bulk(client).collect(collector.claim(run, "first", 100, Duration.ofMinutes(2)), "DAILY_PRICE", topic);
        assertThat(count("operations.outbox_events")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status FROM operations.outbox_events WHERE instrument_id=:id").param("id", instrument)
                .query(String.class).single()).isEqualTo("PUBLISHED");
        assertThat(jdbc.sql("SELECT status FROM operations.outbox_events WHERE instrument_id=:id").param("id", second)
                .query(String.class).single()).isEqualTo("FAILED_TERMINAL");
        assertThat(count("operations.dead_letter_records")).isEqualTo(1);
        assertThat(deadLetters.inspect(10).getFirst().reason()).contains("currency");
        assertThat(count("market_data.daily_bar_observations")).isEqualTo(1);
        assertThat(count("operations.kafka_inbox")).as("the quarantined event left no inbox receipt").isEqualTo(1);
        assertThat(count("operations.ingestion_checkpoints")).isEqualTo(2);
        processor.reconcileCoverage();
        assertThat(jdbc.sql("SELECT status FROM operations.ingestion_run_items WHERE instrument_id=:id").param("id", second)
                .query(String.class).single()).as("a quarantined item never completes").isEqualTo("STAGED");
        assertThat(count("operations.data_coverage")).isZero();
    }

    @Test void interruptedCollectionResumesWithoutDuplicatesOrLosses() {
        calendar(DAY, DAY);
        dailyJob();
        UUID second = secondInstrument();
        var run = plan("interrupted");
        var client = mock(AlpacaDailyDataClient.class);
        when(client.bars(anyList(), eq(DAY), eq("RAW"), isNull(), eq("")))
                .thenReturn(page(Map.of("FIX", List.of(price("100", "USD"))), "p2"));
        when(client.bars(anyList(), eq(DAY), eq("RAW"), isNull(), eq("p2")))
                .thenThrow(new IllegalStateException("connection reset"));
        bulk(client).collect(collector.claim(run, "first", 100, Duration.ofMinutes(2)), "DAILY_PRICE", topic);
        // The first page is durable and canonical; the run waits for its audited retry.
        assertThat(count("market_data.daily_bar_observations")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM operations.ingestion_run_items WHERE status='WAITING_RETRY'")).isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM operations.ingestion_attempts WHERE status='FAILED'")).isEqualTo(2);
        jdbc.sql("UPDATE operations.ingestion_run_items SET next_retry_at=clock_timestamp()").update();
        when(client.bars(anyList(), eq(DAY), eq("RAW"), isNull(), eq("p2")))
                .thenReturn(page(Map.of("FIX", List.of(price("100", "USD")), "SECOND", List.of(price("100", "USD"))), ""));
        bulk(client).collect(collector.claim(run, "restart", 100, Duration.ofMinutes(2)), "DAILY_PRICE", topic);
        processor.reconcileCoverage();
        assertThat(count("market_data.daily_bar_observations")).isEqualTo(2);
        assertThat(count("market_data.observations")).isEqualTo(2);
        assertThat(count("operations.outbox_events")).isEqualTo(2);
        assertThat(count("operations.kafka_inbox")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT DISTINCT status FROM operations.outbox_events").query(String.class).list())
                .containsExactly("PUBLISHED");
        assertThat(text("SELECT status FROM operations.ingestion_runs WHERE ingestion_run_id='" + run + "'")).isEqualTo("COMPLETE");
        assertThat(scalar("SELECT COUNT(*) FROM operations.data_coverage WHERE valid")).isEqualTo(1);
        // The resumed request kept the persisted symbol set and page token: page 1 was not fetched again.
        verify(client, times(1)).bars(eq(List.of("FIX", "SECOND")), eq(DAY), eq("RAW"), isNull(), eq(""));
        assertThat(second).isNotEqualTo(instrument);
    }

    @Test void transientCanonicalFailureRollsBackThePageAndTheRetryWritesOnce() {
        calendar(DAY, DAY);
        dailyJob();
        var run = plan("transient");
        var client = mock(AlpacaDailyDataClient.class);
        when(client.bars(anyList(), eq(DAY), eq("RAW"), isNull(), eq("")))
                .thenReturn(page(Map.of("FIX", List.of(price("100", "USD"))), ""));
        // Fail the first canonical write after the inbox row: the whole page transaction must roll back.
        var failing = new DurableObservationProcessor(source, tx, projection, group) {
            int calls;
            @Override public boolean process(com.quantplatform.ingestion.ObservationEvent event, String topic, int partition, long offset) {
                if (calls++ == 0) throw new org.springframework.dao.TransientDataAccessResourceException("database restarted");
                return super.process(event, topic, partition, offset);
            }
        };
        var store = new IngestionRunStore(source, tx, CanonicalJson.MAPPER, new OutboxDeliveryWorker(source, tx, failing,
                deadLetters, topic, retryAttempts, Duration.ofMillis(10), Duration.ofMinutes(2)));
        bulk(client, store).collect(store.claim(run, "first", 100, Duration.ofMinutes(2)), "DAILY_PRICE", topic);
        assertThat(count("operations.outbox_events")).isZero();
        assertThat(count("operations.source_artifacts")).isZero();
        assertThat(count("operations.kafka_inbox")).isZero();
        assertThat(count("market_data.daily_bar_observations")).isZero();
        assertThat(text("SELECT status FROM operations.ingestion_run_items")).isEqualTo("WAITING_RETRY");
        assertThat(text("SELECT failure->>'code' FROM operations.ingestion_run_items")).isEqualTo("BULK_REQUEST_FAILED");
        jdbc.sql("UPDATE operations.ingestion_run_items SET next_retry_at=clock_timestamp()").update();
        bulk(client, store).collect(store.claim(run, "retry", 100, Duration.ofMinutes(2)), "DAILY_PRICE", topic);
        processor.reconcileCoverage();
        assertThat(count("market_data.daily_bar_observations")).isEqualTo(1);
        assertThat(count("operations.kafka_inbox")).isEqualTo(1);
        assertThat(text("SELECT status FROM operations.ingestion_runs")).isEqualTo("COMPLETE");
        assertThat(scalar("SELECT COUNT(*) FROM operations.ingestion_attempts")).isEqualTo(2);
    }

    @Test void anEventQueuedBehindAPendingEventOfTheSameInstrumentWaitsForTheDrainOrder() {
        stage(DAY, "before-upgrade", "100");                      // staged without acceptance (backlog)
        stageWith(collector, DAY.plusDays(1), "scheduled", "100");
        assertThat(jdbc.sql("SELECT status FROM operations.outbox_events ORDER BY created_at,event_id").query(String.class).list())
                .containsExactly("PENDING", "PENDING");
        assertThat(count("market_data.observations")).isZero();
        assertThat(worker.drain(10)).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT o.economic_time::date::text FROM market_data.observations o
                JOIN operations.kafka_inbox i USING (observation_key) ORDER BY i.source_offset
                """).query(String.class).list()).containsExactly("2026-09-01", "2026-09-02");
    }

    @Test void auditedReplayOfAQuarantinedStagedEventIsDeliveredByTheDrain() {
        dailyJob();                                   // no trading calendar imported yet
        var client = mock(AlpacaDailyDataClient.class);
        when(client.bars(anyList(), eq(DAY), eq("RAW"), isNull(), eq("")))
                .thenReturn(page(Map.of("FIX", List.of(price("100", "USD"))), ""));
        bulk(client).collect(collector.claim(plan("no-calendar"), "first", 100, Duration.ofMinutes(2)), "DAILY_PRICE", topic);
        assertThat(text("SELECT status FROM operations.outbox_events")).isEqualTo("FAILED_TERMINAL");
        assertThat(count("market_data.daily_bar_observations")).isZero();
        var entry = deadLetters.inspect(10).getFirst();
        assertThat(entry.reason()).contains("no open trading session");
        calendar(DAY, DAY);                           // the operator imports the missing session
        UUID request = UUID.randomUUID();
        assertThat(deadLetters.replay(entry.id(), request, "test-operator", "calendar imported")).isTrue();
        assertThat(text("SELECT status FROM operations.outbox_events")).isEqualTo("PENDING");
        assertThat(worker.drain(10)).isEqualTo(1);
        assertThat(count("market_data.daily_bar_observations")).isEqualTo(1);
        assertThat(text("SELECT status FROM operations.dead_letter_replays")).isEqualTo("PUBLISHED");
        processor.reconcileCoverage();
        assertThat(text("SELECT status FROM operations.ingestion_runs")).isEqualTo("COMPLETE");
    }

    @Test void theCollectorRoleAloneCanStageAcceptQuarantineReplayAndReconcile() {
        calendar(DAY, DAY);
        dailyJob();
        jdbc.sql("ALTER ROLE quant_ingestion LOGIN PASSWORD 'collector-role-test'").update();
        var url = jdbc.sql("SELECT current_database()").query(String.class).single();
        var role = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://%s:%d/%s".formatted(
                DB.getHost(), DB.getMappedPort(5432), url), "quant_ingestion", "collector-role-test");
        var roleTx = new org.springframework.jdbc.datasource.DataSourceTransactionManager(role);
        var roleProcessor = new DurableObservationProcessor(role, roleTx, new LegacyProjection(role), group);
        var roleDeadLetters = new DeadLetterService(role, roleTx, topic, group);
        var roleWorker = new OutboxDeliveryWorker(role, roleTx, roleProcessor, roleDeadLetters, topic, retryAttempts,
                Duration.ofMillis(10), Duration.ofMinutes(2));
        var roleStore = new IngestionRunStore(role, roleTx, CanonicalJson.MAPPER, roleWorker);
        UUID second = secondInstrument();
        var client = mock(AlpacaDailyDataClient.class);
        when(client.bars(anyList(), eq(DAY), eq("RAW"), isNull(), eq("")))
                .thenReturn(page(Map.of("FIX", List.of(price("100", "USD")), "SECOND", List.of(price("100", "EUR"))), ""));
        var run = roleStore.plan(new IngestionPlan(job, sp500, nasdaq, DAY, DAY, "backfill", "role", "test"));
        bulk(client, roleStore).collect(roleStore.claim(run, "role", 100, Duration.ofMinutes(2)), "DAILY_PRICE", topic);
        assertThat(count("market_data.daily_bar_observations")).isEqualTo(1);
        assertThat(count("operations.dead_letter_records")).isEqualTo(1);
        // Legacy STOCK_BAR projection into public.market_bars, staged and accepted by the same role.
        var bars = roleStore.plan(new IngestionPlan(job, sp500, nasdaq, DAY.plusDays(1), DAY.plusDays(1), "backfill", "bar", "test"));
        for (var lease : roleStore.claim(bars, "role", 100, Duration.ofMinutes(2))) {
            if (!lease.instrumentId().equals(instrument)) continue;
            roleStore.stage(lease, new SourceArtifact("bar", "test://bar", Instant.now(), "fixture", "{}"),
                    List.of(new OutboxObservation(topic, "STOCK_BAR", DAY.plusDays(1).atTime(4, 0).toInstant(ZoneOffset.UTC), "RAW",
                            payload(DAY.plusDays(1), "100"))), "{}", true);
        }
        assertThat(scalar("SELECT COUNT(*) FROM public.market_bars WHERE symbol='FIX'")).isEqualTo(1);
        // Fix the instrument currency (stands in for a reviewed reference correction), then replay as the role.
        jdbc.sql("UPDATE reference.instruments SET currency='EUR' WHERE instrument_id=:id").param("id", second).update();
        var entry = roleDeadLetters.inspect(10).getFirst();
        assertThat(roleDeadLetters.replay(entry.id(), UUID.randomUUID(), "role-operator", "currency reviewed")).isTrue();
        assertThat(roleWorker.drain(10)).isEqualTo(1);
        assertThat(roleProcessor.reconcileCoverage()).isGreaterThanOrEqualTo(2);
        assertThat(count("market_data.daily_bar_observations")).isEqualTo(2);
        assertThat(text("SELECT status FROM operations.ingestion_runs WHERE ingestion_run_id='" + run + "'")).isEqualTo("COMPLETE");
    }

    com.quantplatform.ingestion.ObservationEvent stageWith(IngestionRunStore store, LocalDate date, String request, String close) {
        var run = store.plan(new IngestionPlan(job, sp500, nasdaq, date, date, "backfill", request, "test"));
        var lease = store.claim(run, "fixture", 1, Duration.ofMinutes(2)).getFirst();
        UUID event = store.stage(lease, new SourceArtifact(date + request, "test://provider/" + date,
                date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), "fixture", "{}"),
                List.of(new OutboxObservation(topic, "STOCK_BAR", date.atTime(4, 0).toInstant(ZoneOffset.UTC), "RAW",
                        payload(date, close))), "{}", true).getFirst();
        return jdbc.sql("SELECT partition_key,payload::text FROM operations.outbox_events WHERE event_id=:id")
                .param("id", event).query((rs, row) -> com.quantplatform.ingestion.ObservationEvent.parse(rs.getString(1),
                        rs.getString(2))).single();
    }

    void dailyJob() {
        jdbc.sql("UPDATE operations.job_definitions SET configuration=CAST(:config AS jsonb) WHERE job_definition_id=:id")
                .param("id", job).param("config", CanonicalJson.write(Map.of("eventType", "DAILY_PRICE", "adjustment", "RAW")))
                .update();
    }

    UUID plan(String request) {
        return collector.plan(new IngestionPlan(job, sp500, nasdaq, DAY, DAY, "backfill", request, "test"));
    }

    Map<String, Object> price(String close, String currency) {
        var result = new TreeMap<String, Object>();
        result.put("time", DAY + "T04:00:00Z"); result.put("sessionDate", DAY.toString());
        result.put("currency", currency); result.put("feed", "sip");
        result.put("open", close); result.put("high", close); result.put("low", close); result.put("close", close);
        result.put("volume", 1000); result.put("tradeCount", 10);
        return result;
    }

    AlpacaDailyDataClient.Page page(Map<String, List<Map<String, Object>>> values, String token) {
        return new AlpacaDailyDataClient.Page(values, token, CanonicalJson.write(values), "test://bulk/" + token, Instant.now());
    }

    BulkDailyCollector bulk(AlpacaDailyDataClient client) { return bulk(client, collector); }

    BulkDailyCollector bulk(AlpacaDailyDataClient client, IngestionRunStore store) {
        return new BulkDailyCollector(source, tx, store, client, new IngestionProperties("backfill", DAY, DAY, null, null,
                false, 100, 3, Duration.ofMinutes(2), Duration.ofMillis(1), Duration.ofSeconds(10), "test://calendar", group));
    }

    UUID secondInstrument() {
        UUID id = jdbc.sql("""
                INSERT INTO reference.instruments (issuer_id,security_type,share_class,currency,primary_exchange_mic,valid_from)
                SELECT issuer_id,'COMMON_STOCK','B','USD','XNAS','2026-08-01' FROM reference.instruments WHERE instrument_id=:id
                RETURNING instrument_id
                """).param("id", instrument).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO reference.instrument_symbols (instrument_id,symbol,exchange_mic,effective_from,source,available_at,observed_at)
                VALUES (:id,'SECOND','XNAS','2026-08-01','fixture','2026-08-01T00:00:00Z','2026-08-01T00:00:00Z')
                """).param("id", id).update();
        for (UUID snapshot : List.of(sp500, nasdaq)) {
            jdbc.sql("""
                    INSERT INTO reference.universe_memberships (universe_snapshot_id,instrument_id,source_symbol,source_exchange_mic,primary_liquid_class)
                    VALUES (:snapshot,:id,'SECOND','XNAS',false)
                    """).param("snapshot", snapshot).param("id", id).update();
            jdbc.sql("UPDATE reference.universe_snapshots SET expected_member_count=2,imported_member_count=2 WHERE universe_snapshot_id=:id")
                    .param("id", snapshot).update();
        }
        return id;
    }
}
