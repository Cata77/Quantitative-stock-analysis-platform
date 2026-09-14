package com.quantplatform.marketdata.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class IngestionRunStoreIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> TIMESCALE = new PostgreSQLContainer<>(DockerImageName
            .parse("timescale/timescaledb:2.29.1-pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("postgres").withUsername("postgres").withPassword("postgres");

    private JdbcClient jdbc;
    private DriverManagerDataSource dataSource;
    private IngestionRunStore store;
    private UUID jobId;
    private UUID datasetId;
    private UUID sp500;
    private UUID nasdaq;

    @BeforeEach
    void createIsolatedDatabase() throws Exception {
        String database = "operations_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(TIMESCALE.getJdbcUrl(), "postgres", "postgres");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        dataSource = new DriverManagerDataSource("jdbc:postgresql://%s:%d/%s".formatted(
                TIMESCALE.getHost(), TIMESCALE.getMappedPort(5432), database), "postgres", "postgres");
        // Enable the extension using the simple protocol before any prepared statement
        // causes Timescale's loader to initialize this new database connection.
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE");
        }
        jdbc = JdbcClient.create(dataSource);
        Flyway.configure().dataSource(dataSource).defaultSchema("operations").schemas("operations")
                .cleanDisabled(true).locations("classpath:db/migration").load().migrate();
        store = newStore();
        var provider = jdbc.sql("""
                INSERT INTO operations.data_providers (code, name, license_notes)
                VALUES ('fixture', 'Fixture provider', 'Test data only') RETURNING provider_id
                """).query(UUID.class).single();
        datasetId = jdbc.sql("""
                INSERT INTO operations.datasets (provider_id, code, version, license_notes)
                VALUES (:provider, 'daily-raw', '1', 'Test data only') RETURNING dataset_id
                """).param("provider", provider).query(UUID.class).single();
        jobId = jdbc.sql("""
                INSERT INTO operations.job_definitions (dataset_id, code, version, max_attempts)
                VALUES (:dataset, 'fixture-bars', '1', 2) RETURNING job_definition_id
                """).param("dataset", datasetId).query(UUID.class).single();
        var issuer = jdbc.sql("INSERT INTO reference.issuers (legal_name) VALUES ('Fixture issuer') RETURNING issuer_id")
                .query(UUID.class).single();
        var first = instrument(issuer, "A");
        var second = instrument(issuer, "B");
        sp500 = snapshot("SP500", List.of(first, second));
        nasdaq = snapshot("NASDAQ100", List.of(first));
    }

    @Test
    void repeatedAndConcurrentPlanningReusesTheExactInstrumentUnionAcrossInvocationModes() throws Exception {
        var plan = plan("normal");
        UUID run;
        var nextInvocation = new IngestionPlan(jobId, sp500, nasdaq, plan.windowStart(), plan.windowEnd(),
                "sync-and-exit", "normal", "new-process-version");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .<Callable<UUID>>mapToObj(index -> () -> newStore().plan(nextInvocation)).toList();
            var results = executor.invokeAll(tasks, 10, TimeUnit.SECONDS);
            run = results.getFirst().get();
            for (var future : results) {
                assertThat(future.get()).isEqualTo(run);
            }
        }
        assertThat(store.plan(plan)).isEqualTo(run);
        assertThat(count("ingestion_runs")).isEqualTo(1);
        assertThat(count("ingestion_run_snapshots")).isEqualTo(2);
        assertThat(count("ingestion_run_items")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT expected_count FROM operations.ingestion_runs").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void partialMismatchedAndUnreconciledSnapshotsCannotPlanWork() {
        assertThatThrownBy(() -> store.plan(new IngestionPlan(jobId, nasdaq, sp500,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-07"), "backfill", "bad", "test")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SP500");
        jdbc.sql("UPDATE reference.universe_snapshots SET completeness_status = 'PARTIAL' WHERE universe_snapshot_id = :id")
                .param("id", sp500).update();
        assertThatThrownBy(() -> store.plan(plan("partial"))).isInstanceOf(IllegalArgumentException.class);
        jdbc.sql("""
                UPDATE reference.universe_snapshots SET completeness_status = 'COMPLETE',
                    expected_member_count = 3, imported_member_count = 3 WHERE universe_snapshot_id = :id
                """).param("id", sp500).update();
        assertThatThrownBy(() -> store.plan(plan("wrong-count"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("ingestion_runs")).isZero();
    }

    @Test
    void workersSkipLockedItemsAndNeverShareALiveLease() throws Exception {
        UUID run = store.plan(plan("normal"));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID locked;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    SELECT ingestion_run_item_id FROM operations.ingestion_run_items
                    ORDER BY ingestion_run_item_id LIMIT 1 FOR UPDATE
                    """)) {
                rows.next();
                locked = rows.getObject(1, UUID.class);
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var claimed = executor.submit(() -> store.claim(run, "worker-a", 10, Duration.ofMinutes(1)))
                        .get(5, TimeUnit.SECONDS);
                assertThat(claimed).hasSize(1);
                assertThat(claimed.getFirst().itemId()).isNotEqualTo(locked);
                connection.rollback();
                var other = newStore().claim(run, "worker-b", 10, Duration.ofMinutes(1));
                assertThat(other).hasSize(1);
                assertThat(other.getFirst().itemId()).isEqualTo(locked);
            }
        }
        assertThat(store.claim(run, "worker-c", 10, Duration.ofMinutes(1))).isEmpty();
        assertThat(count("ingestion_attempts")).isEqualTo(2);
    }

    @Test
    void restartResumesTheCheckpointAndFencesTheOldWorker() {
        UUID run = store.plan(plan("normal"));
        var leases = store.claim(run, "before-restart", 2, Duration.ofMinutes(1));
        var first = leases.getFirst();
        var second = leases.getLast();
        var eventIds = store.stage(first, source("page-1"), List.of(observation("100.00")), "{\"page\":2}", false);
        store.stage(second, source("second-instrument"), List.of(observation("200")), "{}", true);
        expire(first);

        var resumed = newStore().claim(run, "after-restart", 10, Duration.ofMinutes(1));
        assertThat(resumed).hasSize(1);
        var replacement = resumed.getFirst();
        assertThat(replacement.itemId()).isEqualTo(first.itemId());
        assertThat(replacement.token()).isNotEqualTo(first.token());
        assertThat(replacement.attemptNumber()).isEqualTo(2);
        assertThat(replacement.checkpointJson()).contains("\"page\": 2");
        assertThatThrownBy(() -> store.stage(first, source("stale"), List.of(observation("1")), "{}", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lease");
        assertThatThrownBy(() -> store.retry(first, "STALE", "old worker", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.stage(replacement, source("page-1"), List.of(observation("100")), "{}", true))
                .isEqualTo(eventIds);
        assertThat(count("outbox_events")).isEqualTo(2);
        assertThat(count("source_artifacts")).isEqualTo(2);
        assertThat(status(run)).isEqualTo("VALIDATING");
        assertThat(count("data_coverage")).isZero();
        assertThat(count("data_watermarks")).isZero();
        assertThat(newStore().claim(run, "next-start", 10, Duration.ofMinutes(1))).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM operations.ingestion_attempts WHERE status = 'LEASE_EXPIRED'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void retriesRespectBackoffAndExhaustTheAttemptBudget() {
        UUID run = store.plan(plan("normal"));
        var lease = store.claim(run, "worker", 2, Duration.ofMinutes(1)).getFirst();
        store.retry(lease, "PROVIDER_QUOTA", "retry later", Duration.ofHours(1));
        assertThat(store.claim(run, "early", 2, Duration.ofMinutes(1))).isEmpty();
        jdbc.sql("""
                UPDATE operations.ingestion_run_items SET next_retry_at = clock_timestamp() - INTERVAL '1 second'
                WHERE ingestion_run_item_id = :item
                """).param("item", lease.itemId()).update();
        var retry = store.claim(run, "retry", 2, Duration.ofMinutes(1)).getFirst();
        store.retry(retry, "PROVIDER_QUOTA", "still unavailable", Duration.ofSeconds(1));
        assertThat(status(run)).isEqualTo("FAILED");
        assertThat(store.claim(run, "again", 2, Duration.ofMinutes(1))).isEmpty();
        assertThat(jdbc.sql("SELECT failure ->> 'code' FROM operations.ingestion_run_items WHERE ingestion_run_item_id = :item")
                .param("item", lease.itemId()).query(String.class).single()).isEqualTo("PROVIDER_QUOTA");
    }

    @Test
    void crashOnLastAttemptBecomesTerminalInsteadOfRemainingUnclaimable() {
        UUID run = store.plan(plan("normal"));
        var first = store.claim(run, "worker", 2, Duration.ofMinutes(1)).getFirst();
        expire(first);
        var last = store.claim(run, "restart", 2, Duration.ofMinutes(1)).getFirst();
        expire(last);
        assertThat(store.claim(run, "restart-again", 2, Duration.ofMinutes(1))).isEmpty();
        assertThat(status(run)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failure ->> 'code' FROM operations.ingestion_run_items WHERE ingestion_run_item_id = :item")
                .param("item", first.itemId()).query(String.class).single()).isEqualTo("ATTEMPTS_EXHAUSTED");
    }

    @Test
    void invalidPageRollsBackArtifactOutboxCheckpointAndStatusTogether() {
        UUID run = store.plan(plan("normal"));
        var lease = store.claim(run, "worker", 1, Duration.ofMinutes(1)).getFirst();
        var invalid = new OutboxObservation("market-data-events-v2", "DAILY_BAR", Instant.parse("2026-09-01T20:00:00Z"),
                "RAW", "[]");
        assertThatThrownBy(() -> store.stage(lease, source("bad-page"), List.of(observation("100"), invalid), "{}", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("source_artifacts")).isZero();
        assertThat(count("outbox_events")).isZero();
        assertThat(count("ingestion_item_artifacts")).isZero();
        assertThat(count("ingestion_item_events")).isZero();
        assertThat(count("ingestion_checkpoints")).isZero();
        assertThat(status(run)).isEqualTo("RUNNING");
        assertThat(store.stage(lease, source("good-page"), List.of(observation("100")), "{}", true)).hasSize(1);
    }

    @Test
    void observationIdentitySurvivesNewRunsAndRetainsCorrectionsAndDecimalPrecision() {
        UUID run = store.plan(plan("normal"));
        var original = store.claim(run, "worker", 2, Duration.ofMinutes(1)).getFirst();
        var firstIds = store.stage(original, source("first-response"), List.of(observation("100.1234567890123456789")), "{}", true);
        UUID refresh = store.plan(plan("explicit-correction-2026-09-12"));
        var repeated = store.claim(refresh, "worker", 2, Duration.ofMinutes(1)).stream()
                .filter(lease -> lease.instrumentId().equals(original.instrumentId())).findFirst().orElseThrow();
        var equivalent = new OutboxObservation("market-data-events-v2", "DAILY_BAR", Instant.parse("2026-09-01T20:00:00Z"),
                "RAW", "{\"volume\":1000,\"close\":100.12345678901234567890}");
        assertThat(store.stage(repeated, source("second-response"), List.of(equivalent), "{}", false)).isEqualTo(firstIds);
        var correction = store.stage(repeated, source("correction"), List.of(observation("100.1234567890123456788")), "{}", true);
        assertThat(correction).doesNotContainAnyElementsOf(firstIds);
        assertThat(count("outbox_events")).isEqualTo(2);
        assertThat(count("source_artifacts")).isEqualTo(3);
        assertThat(count("ingestion_item_artifacts")).isEqualTo(3);
        assertThat(count("ingestion_item_events")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM operations.outbox_events WHERE partition_key = instrument_id")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT payload -> 'payload' ->> 'close' FROM operations.outbox_events WHERE event_id = :id")
                .param("id", firstIds.getFirst()).query(String.class).single()).isEqualTo("100.1234567890123456789");
        assertThat(jdbc.sql("SELECT payload_hash FROM operations.outbox_events WHERE event_id = :id")
                .param("id", firstIds.getFirst()).query(String.class).single()).isEqualTo(ObservationIdentity.contentHash(
                        ObservationIdentity.canonicalJson(JsonMapper.builder().build(), jdbc.sql(
                                "SELECT payload::text FROM operations.outbox_events WHERE event_id = :id")
                                .param("id", firstIds.getFirst()).query(String.class).single())));
    }

    @Test
    void inboxDeduplicatesBothIdentitiesAndRollsBackWithCanonicalWrites() {
        UUID event = UUID.randomUUID();
        String key = "a".repeat(64);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insertInbox(event, key, "scoring");
            jdbc.sql("INSERT INTO reference.issuers (legal_name) VALUES ('canonical rollback fixture')").update();
            throw new IllegalStateException("simulated canonical failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("kafka_inbox")).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reference.issuers WHERE legal_name = 'canonical rollback fixture'")
                .query(Integer.class).single()).isZero();
        insertInbox(event, key, "scoring");
        assertThatThrownBy(() -> insertInbox(event, "b".repeat(64), "scoring"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> insertInbox(UUID.randomUUID(), key, "scoring"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        insertInbox(event, key, "another-consumer");
        assertThat(count("kafka_inbox")).isEqualTo(2);
    }

    @Test
    void databaseRejectsFalseCompletionAndUnbackedWatermarks() {
        UUID run = store.plan(plan("normal"));
        assertThatThrownBy(() -> jdbc.sql("UPDATE operations.ingestion_runs SET status = 'COMPLETE' WHERE ingestion_run_id = :run")
                .param("run", run).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO operations.data_watermarks (dataset_id, partition_key, coverage_start, complete_through)
                VALUES (:dataset, 'universe', '2026-09-01', '2026-09-07')
                """).param("dataset", datasetId).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var lease = store.claim(run, "worker", 1, Duration.ofMinutes(1)).getFirst();
        store.stage(lease, source("page"), List.of(observation("100")), "{}", true);
        assertThatThrownBy(() -> jdbc.sql("UPDATE operations.outbox_events SET status = 'PUBLISHED'").update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE operations.outbox_events SET partition_key = :wrong")
                .param("wrong", UUID.randomUUID()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void insertInbox(UUID event, String key, String consumer) {
        jdbc.sql("""
                INSERT INTO operations.kafka_inbox (consumer_name, event_id, observation_key, payload_hash,
                    source_topic, source_partition, source_offset, processing_result)
                VALUES (:consumer, :event, :key, :key, 'fixture', 0, 42, 'ACCEPTED')
                """).param("consumer", consumer).param("event", event).param("key", key).update();
    }

    private IngestionRunStore newStore() {
        return new IngestionRunStore(dataSource, new DataSourceTransactionManager(dataSource), JsonMapper.builder().build());
    }

    private IngestionPlan plan(String request) {
        return new IngestionPlan(jobId, sp500, nasdaq, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-07"),
                "backfill", request, "test");
    }

    private UUID instrument(UUID issuer, String shareClass) {
        return jdbc.sql("""
                INSERT INTO reference.instruments (issuer_id, security_type, share_class, currency, primary_exchange_mic, valid_from)
                VALUES (:issuer, 'COMMON_STOCK', :shareClass, 'USD', 'XNAS', '2026-08-27') RETURNING instrument_id
                """).param("issuer", issuer).param("shareClass", shareClass).query(UUID.class).single();
    }

    private UUID snapshot(String universe, List<UUID> members) {
        UUID snapshot = jdbc.sql("""
                INSERT INTO reference.universe_snapshots (universe_id, effective_date, observed_at, source, source_checksum,
                    import_mode, completeness_status, expected_member_count, imported_member_count)
                SELECT universe_id, '2026-08-27', '2026-08-27T22:00:00Z', 'fixture', :checksum,
                    'CURRENT_SNAPSHOT_FORWARD', 'COMPLETE', :count, :count
                FROM reference.universes WHERE code = :code RETURNING universe_snapshot_id
                """).param("checksum", "f".repeat(64)).param("count", members.size()).param("code", universe)
                .query(UUID.class).single();
        for (int i = 0; i < members.size(); i++) {
            jdbc.sql("""
                    INSERT INTO reference.universe_memberships
                        (universe_snapshot_id, instrument_id, source_symbol, source_exchange_mic, primary_liquid_class)
                    VALUES (:snapshot, :instrument, :symbol, 'XNAS', :primary)
                    """).param("snapshot", snapshot).param("instrument", members.get(i))
                    .param("symbol", "FIX" + i).param("primary", i == 0).update();
        }
        return snapshot;
    }

    private SourceArtifact source(String request) {
        return new SourceArtifact(request, "test://provider/" + request, Instant.parse("2026-09-08T12:00:00Z"),
                "fixture-v1", "{\"providerResponse\":\"" + request + "\"}");
    }

    private OutboxObservation observation(String close) {
        return new OutboxObservation("market-data-events-v2", "DAILY_BAR", Instant.parse("2026-09-01T20:00:00Z"), "RAW",
                "{\"close\":" + close + ",\"volume\":1000}");
    }

    private void expire(JobLease lease) {
        jdbc.sql("UPDATE operations.ingestion_run_items SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE ingestion_run_item_id = :item")
                .param("item", lease.itemId()).update();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM operations." + table).query(Integer.class).single();
    }

    private String status(UUID run) {
        return jdbc.sql("SELECT status FROM operations.ingestion_runs WHERE ingestion_run_id = :run")
                .param("run", run).query(String.class).single();
    }
}
