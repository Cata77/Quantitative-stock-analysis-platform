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
class DurableDeliveryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName
            .parse("timescale/timescaledb:2.29.1-pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("postgres").withUsername("postgres").withPassword("postgres");
    @Container static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

    DriverManagerDataSource source;
    DataSourceTransactionManager tx;
    JdbcClient jdbc;
    IngestionRunStore jobs;
    OutboxStore outbox;
    OutboxRelay relay;
    DurableObservationProcessor processor;
    MarketDataEventProcessor projection;
    DeadLetterService deadLetters;
    DefaultKafkaProducerFactory<String, String> stringFactory;
    DefaultKafkaProducerFactory<String, byte[]> bytesFactory;
    KafkaTemplate<String, String> producer;
    KafkaTemplate<String, byte[]> bytesProducer;
    String topic, group;
    UUID dataset, job, instrument, sp500, nasdaq;
    final DeliveryProperties delivery = new DeliveryProperties(3, 50, Duration.ofMinutes(2),
            Duration.ofSeconds(15), Duration.ofMillis(1));
    KafkaMessageListenerContainer<String, byte[]> container;

    @BeforeEach void setup() throws Exception {
        String database = "delivery_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(DB.getJdbcUrl(), "postgres", "postgres");
             var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + database); }
        source = new DriverManagerDataSource("jdbc:postgresql://%s:%d/%s".formatted(
                DB.getHost(), DB.getMappedPort(5432), database), "postgres", "postgres");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE");
        }
        Flyway.configure().dataSource(source).defaultSchema("operations").schemas("operations")
                .locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(source);
        tx = new DataSourceTransactionManager(source);
        topic = "observations-" + UUID.randomUUID();
        group = "consumer-" + UUID.randomUUID();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1), new NewTopic(topic + "-dlq", 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }
        var config = new HashMap<String, Object>();
        config.put("bootstrap.servers", KAFKA.getBootstrapServers());
        config.put("acks", "all");
        config.put("enable.idempotence", true);
        stringFactory = new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer());
        bytesFactory = new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new ByteArraySerializer());
        producer = new KafkaTemplate<>(stringFactory);
        bytesProducer = new KafkaTemplate<>(bytesFactory);
        jobs = new IngestionRunStore(source, tx, CanonicalJson.MAPPER);
        outbox = new OutboxStore(source, tx, delivery);
        relay = new OutboxRelay(outbox, producer, delivery);
        projection = mock(MarketDataEventProcessor.class);
        processor = new DurableObservationProcessor(source, tx, projection, group);
        deadLetters = new DeadLetterService(source, tx, bytesProducer,
                new ScoringProperties(topic, topic + "-dlq", 1, 1, Duration.ofMillis(10), 2,
                        Duration.ofDays(365), 2, new ScoringProperties.Elasticsearch(false, "http://localhost", "companies")), group);
        UUID provider = jdbc.sql("INSERT INTO operations.data_providers (code,name,license_notes) VALUES ('fixture','Fixture','test') RETURNING provider_id")
                .query(UUID.class).single();
        dataset = jdbc.sql("INSERT INTO operations.datasets (provider_id,code,version,license_notes) VALUES (:id,'bars','1','test') RETURNING dataset_id")
                .param("id", provider).query(UUID.class).single();
        job = jdbc.sql("""
                INSERT INTO operations.job_definitions (dataset_id,code,version,configuration,max_attempts)
                VALUES (:id,'fixture','1','{"eventType":"STOCK_BAR"}',3) RETURNING job_definition_id
                """).param("id", dataset).query(UUID.class).single();
        UUID issuer = jdbc.sql("INSERT INTO reference.issuers (legal_name) VALUES ('Fixture') RETURNING issuer_id").query(UUID.class).single();
        instrument = jdbc.sql("""
                INSERT INTO reference.instruments (issuer_id,security_type,share_class,currency,primary_exchange_mic,valid_from)
                VALUES (:id,'COMMON_STOCK','A','USD','XNAS','2026-08-01') RETURNING instrument_id
                """).param("id", issuer).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO reference.instrument_symbols (instrument_id,symbol,exchange_mic,effective_from,source,available_at,observed_at)
                VALUES (:id,'FIX','XNAS','2026-08-01','fixture','2026-08-01T00:00:00Z','2026-08-01T00:00:00Z')
                """).param("id", instrument).update();
        sp500 = snapshot("SP500");
        nasdaq = snapshot("NASDAQ100");
    }

    @AfterEach void close() {
        if (container != null) container.stop();
        if (stringFactory != null) stringFactory.destroy();
        if (bytesFactory != null) bytesFactory.destroy();
    }

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

    IngestionCoordinator coordinator(AlpacaStockMarketClient alpaca, String now) {
        var settings = new IngestionProperties("catch-up-and-serve", LocalDate.parse("2026-09-01"), null, null, null,
                false, 100, 3, Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofSeconds(10),
                "https://paper-api.alpaca.markets", group);
        return new IngestionCoordinator(source, tx, new IngestionRunStore(source, tx, CanonicalJson.MAPPER),
                mock(TradingCalendarLoader.class), alpaca, mock(AlphaVantageFundamentalClient.class),
                new MarketDataProperties(true, List.of("IGNORED"), topic, 1, Duration.ofSeconds(10), Duration.ofSeconds(10),
                        true, false, 1, "1Day", false),
                new AlpacaProperties(URI.create("https://data.alpaca.markets"), "", "", "iex"), settings, clock(now));
    }

    void acceptStaged() {
        for (var event : jdbc.sql("SELECT partition_key,payload::text FROM operations.outbox_events")
                .query((rs, row) -> ObservationEvent.parse(rs.getString(1), rs.getString(2))).list())
            processor.process(event, topic, 0, 0);
        processor.reconcileCoverage();
    }

    ObservationEvent stage(LocalDate date, String request, String close) {
        var run = jobs.plan(new IngestionPlan(job, sp500, nasdaq, date, date, "backfill", request, "test"));
        var lease = jobs.claim(run, "fixture", 1, Duration.ofMinutes(2)).getFirst();
        UUID event = jobs.stage(lease, new SourceArtifact(date + request, "test://provider/" + date,
                date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), "fixture", "{}"),
                List.of(new OutboxObservation(topic, "STOCK_BAR", date.atTime(4, 0).toInstant(ZoneOffset.UTC), "RAW", payload(date, close))), "{}", true).getFirst();
        return jdbc.sql("SELECT partition_key,payload::text FROM operations.outbox_events WHERE event_id=:id")
                .param("id", event).query((rs, row) -> ObservationEvent.parse(rs.getString(1), rs.getString(2))).single();
    }

    String payload(LocalDate date, String close) {
        return """
                {"time":"%sT04:00:00Z","open":100,"high":110,"low":90,"close":%s,"volume":1000,"tradeCount":10}
                """.formatted(date, close);
    }

    UUID snapshot(String code) {
        UUID id = jdbc.sql("""
                INSERT INTO reference.universe_snapshots (universe_id,effective_date,observed_at,source,source_checksum,
                    import_mode,completeness_status,expected_member_count,imported_member_count)
                SELECT universe_id,'2026-08-01','2026-08-01T00:00:00Z','fixture',:hash,
                    'CURRENT_SNAPSHOT_FORWARD','COMPLETE',1,1 FROM reference.universes WHERE code=:code RETURNING universe_snapshot_id
                """).param("hash", "f".repeat(64)).param("code", code).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO reference.universe_memberships (universe_snapshot_id,instrument_id,source_symbol,source_exchange_mic,primary_liquid_class)
                VALUES (:snapshot,:instrument,'FIX','XNAS',true)
                """).param("snapshot", id).param("instrument", instrument).update();
        return id;
    }

    void calendar(LocalDate from, LocalDate through) {
        for (var date = from; !date.isAfter(through); date = date.plusDays(1)) {
            boolean holiday = date.getDayOfWeek().getValue() >= 6;
            jdbc.sql("""
                    INSERT INTO reference.trading_sessions (exchange_mic,session_date,opens_at,closes_at,timezone,
                        holiday,early_close,source,available_at,observed_at)
                    VALUES ('XNYS',:date,:open,:close,'America/New_York',:holiday,false,'fixture','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')
                    ON CONFLICT DO NOTHING
                    """).param("date", date).param("holiday", holiday)
                    .param("open", holiday ? null : date.atTime(13, 30).atOffset(ZoneOffset.UTC))
                    .param("close", holiday ? null : date.atTime(20, 0).atOffset(ZoneOffset.UTC)).update();
        }
    }

    void startListener(MessageListener<String, byte[]> listener) {
        var props = new ContainerProperties(topic);
        props.setGroupId(group);
        props.setAckMode(ContainerProperties.AckMode.RECORD);
        props.setSyncCommits(true);
        props.setMessageListener(listener);
        container = new KafkaMessageListenerContainer<>(new DefaultKafkaConsumerFactory<>(consumerConfig(group),
                new StringDeserializer(), new ByteArrayDeserializer()), props);
        var handler = new DefaultErrorHandler(deadLetters::recover, new FixedBackOff(10, 2));
        handler.addNotRetryableExceptions(MarketDataValidationException.class);
        container.setCommonErrorHandler(handler);
        container.start();
    }

    Map<String, Object> consumerConfig(String id) {
        return Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", id,
                "enable.auto.commit", false, "auto.offset.reset", "earliest", "isolation.level", "read_committed");
    }

    KafkaConsumer<String, byte[]> consumer(String id, String input) {
        var consumer = new KafkaConsumer<>(consumerConfig(id), new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of(input));
        return consumer;
    }

    List<ConsumerRecord<String, byte[]>> poll(KafkaConsumer<String, byte[]> consumer, int size) {
        var result = new ArrayList<ConsumerRecord<String, byte[]>>();
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (result.size() < size && System.nanoTime() < end) consumer.poll(Duration.ofMillis(200)).forEach(result::add);
        assertThat(result).hasSize(size);
        return result;
    }

    void send(ObservationEvent event) throws Exception {
        var record = new ProducerRecord<>(topic, event.instrumentId().toString(), event.json());
        record.headers().add(ObservationEvent.HASH_HEADER, event.payloadHash().getBytes(StandardCharsets.UTF_8));
        producer.send(record).get(20, TimeUnit.SECONDS);
    }

    long committedOffset() {
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            var offset = offsets.get(new TopicPartition(topic, 0));
            return offset == null ? -1 : offset.offset();
        } catch (Exception failure) { return -1; }
    }
    void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).as("condition completed within 30 seconds").isTrue();
    }
    Clock clock(String now) { return Clock.fixed(Instant.parse(now), ZoneOffset.UTC); }
    int count(String table) { return scalar("SELECT COUNT(*) FROM " + table); }
    int scalar(String sql) { return jdbc.sql(sql).query(Integer.class).single(); }
    String text(String sql) { return jdbc.sql(sql).query(String.class).single(); }
}
