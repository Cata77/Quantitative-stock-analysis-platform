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
class DurableDeliveryFixture {
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

    IngestionCoordinator coordinator(AlpacaStockMarketClient alpaca, String now) {
        var settings = new IngestionProperties("catch-up-and-serve", LocalDate.parse("2026-09-01"), null, null, null,
                false, 100, 3, Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofSeconds(10),
                "https://paper-api.alpaca.markets", group);
        return new IngestionCoordinator(source, tx, new IngestionRunStore(source, tx, CanonicalJson.MAPPER),
                mock(TradingCalendarLoader.class), alpaca, mock(AlphaVantageFundamentalClient.class),
                new MarketDataProperties(true, List.of("IGNORED"), topic, 1, Duration.ofSeconds(10), Duration.ofSeconds(10),
                        true, false, 1, "1Day", false),
                new AlpacaProperties(URI.create("https://data.alpaca.markets"), "", "", "iex"), settings, clock(now),
                new BulkDailyCollector(source,tx,jobs,bulkAdapter(alpaca),settings),
                new DailyPriceProperties("iex",100,10000,Duration.ofMillis(350),Duration.ofMinutes(16),false,false,14));
    }

    com.quantplatform.marketdata.provider.alpaca.AlpacaDailyDataClient bulkAdapter(AlpacaStockMarketClient alpaca) {
        var client=mock(com.quantplatform.marketdata.provider.alpaca.AlpacaDailyDataClient.class);
        when(client.bars(anyList(),any(),anyString(),any(),anyString())).thenAnswer(call -> {
            List<String> symbols=call.getArgument(0);
            LocalDate date=call.getArgument(1);
            var result=new TreeMap<String,List<Map<String,Object>>>();
            for (String symbol:symbols) {
                var page=alpaca.fetchDurablePage(symbol,date.atStartOfDay(ZoneId.of("America/New_York")).toInstant(),
                        date.plusDays(1).atStartOfDay(ZoneId.of("America/New_York")).toInstant(),call.getArgument(4));
                var values=new ArrayList<Map<String,Object>>();
                for(var bar:page.bars()) {
                    var value=new TreeMap<>(CanonicalJson.readObject(CanonicalJson.MAPPER.writeValueAsString(bar)));
                    value.put("sessionDate",date.toString()); value.put("currency","USD"); value.put("feed","iex");
                    values.add(value);
                }
                result.put(symbol,values);
            }
            return new com.quantplatform.marketdata.provider.alpaca.AlpacaDailyDataClient.Page(result,"","{}","test://bulk/"+date,
                    date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        });
        return client;
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
            for (String exchange : List.of("XNYS","XNAS","ARCX","BATS")) jdbc.sql("""
                    INSERT INTO reference.trading_sessions (exchange_mic,session_date,opens_at,closes_at,timezone,
                        holiday,early_close,source,available_at,observed_at)
                    VALUES (:exchange,:date,:open,:close,'America/New_York',:holiday,false,'fixture','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')
                    ON CONFLICT DO NOTHING
                    """).param("exchange",exchange).param("date", date).param("holiday", holiday)
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
