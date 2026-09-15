package com.quantplatform.scoring.ingestion;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.ingestion.ObservationEvent;
import com.quantplatform.scoring.ingestion.event.FundamentalSnapshot;
import com.quantplatform.scoring.ingestion.event.MarketDataEvent;
import com.quantplatform.scoring.ingestion.event.MarketDataEventType;
import com.quantplatform.scoring.ingestion.event.StockBar;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DurableObservationProcessor {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final MarketDataEventProcessor projection;
    private final String consumer;
    private final DailyMarketDataStore dailyData;
    private final com.quantplatform.scoring.fundamentals.FundamentalDataStore fundamentalData;

    public DurableObservationProcessor(DataSource source, PlatformTransactionManager manager,
            MarketDataEventProcessor projection, @Value("${spring.kafka.consumer.group-id}") String consumer) {
        jdbc = JdbcClient.create(source);
        dailyData = new DailyMarketDataStore(source);
        fundamentalData = new com.quantplatform.scoring.fundamentals.FundamentalDataStore(source);
        transactions = new TransactionTemplate(manager);
        this.projection = projection;
        this.consumer = consumer;
    }

    public boolean process(ObservationEvent event, String topic, int partition, long offset) {
        String payload = CanonicalJson.write(event.payload());
        final StockBar bar;
        final FundamentalSnapshot fundamentals;
        try {
            switch (event.eventType()) {
                case "STOCK_BAR", "DAILY_BAR" -> {
                    bar = CanonicalJson.MAPPER.readValue(payload, StockBar.class);
                    fundamentals = null;
                    if (!bar.time().equals(event.economicTime())) throw new IllegalArgumentException("bar time differs from envelope");
                    if (!event.adjustmentMode().equals("RAW")) throw new IllegalArgumentException("unsupported price adjustment");
                }
                case "DAILY_PRICE", "CORPORATE_ACTION_BATCH" -> {
                    bar = null;
                    fundamentals = null;
                    dailyData.validate(event);
                }
                case "FILING_FACTS", "FUNDAMENTAL_COLLECTION_STATUS", "REGULATORY_FACTS" -> {
                    bar = null; fundamentals = null; fundamentalData.validate(event);
                }
                case "FUNDAMENTAL_SNAPSHOT" -> {
                    bar = null;
                    fundamentals = CanonicalJson.MAPPER.readValue(payload, FundamentalSnapshot.class);
                    if (!event.adjustmentMode().equals("NONE")) throw new IllegalArgumentException("invalid snapshot adjustment");
                }
                default -> throw new IllegalArgumentException("unsupported event type");
            }
        } catch (RuntimeException exception) {
            throw new MarketDataValidationException("invalid economic payload", exception);
        }
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            lock(consumer + ":event:" + event.eventId());
            lock(event.datasetId() + ":" + event.instrumentId() + ":" + event.economicTime());
            int inserted = jdbc.sql("""
                    INSERT INTO operations.kafka_inbox (consumer_name, event_id, observation_key, payload_hash,
                        source_topic, source_partition, source_offset, processing_result)
                    VALUES (:consumer, :event, :key, :hash, :topic, :partition, :offset, 'ACCEPTED')
                    ON CONFLICT DO NOTHING
                    """).param("consumer", consumer).param("event", event.eventId()).param("key", event.observationKey())
                    .param("hash", event.payloadHash()).param("topic", topic).param("partition", partition).param("offset", offset).update();
            var inbox = jdbc.sql("""
                    SELECT event_id, observation_key, payload_hash FROM operations.kafka_inbox
                    WHERE consumer_name = :consumer AND (event_id = :event OR observation_key = :key)
                    """).param("consumer", consumer).param("event", event.eventId()).param("key", event.observationKey())
                    .query((rs, row) -> new Receipt(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
            if (inbox.size() != 1 || !inbox.getFirst().key().equals(event.observationKey())
                    || (inbox.getFirst().eventId().equals(event.eventId()) && !inbox.getFirst().hash().equals(event.payloadHash()))) {
                throw new MarketDataValidationException("event identity was reused with different content");
            }
            jdbc.sql("""
                    INSERT INTO operations.kafka_event_receipts VALUES (:consumer, :event, :key, :hash) ON CONFLICT DO NOTHING
                    """).param("consumer", consumer).param("event", event.eventId()).param("key", event.observationKey())
                    .param("hash", event.payloadHash()).update();
            var receipt = jdbc.sql("""
                    SELECT event_id, observation_key, payload_hash FROM operations.kafka_event_receipts
                    WHERE consumer_name = :consumer AND event_id = :event
                    """).param("consumer", consumer).param("event", event.eventId())
                    .query((rs, row) -> new Receipt(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).single();
            if (!receipt.key().equals(event.observationKey()) || !receipt.hash().equals(event.payloadHash())) {
                throw new MarketDataValidationException("physical event ID collision");
            }
            if (inserted == 0) {
                jdbc.sql("""
                        UPDATE operations.kafka_inbox SET last_observed_at = clock_timestamp(), delivery_count = delivery_count + 1
                        WHERE consumer_name = :consumer AND observation_key = :key
                        """).param("consumer", consumer).param("key", event.observationKey()).update();
                return false;
            }
            var source = jdbc.sql("""
                    SELECT a.source_artifact_id, a.retrieved_at, p.code
                    FROM operations.outbox_events o JOIN operations.source_artifacts a USING (source_artifact_id)
                    JOIN operations.datasets d ON d.dataset_id = o.dataset_id
                    JOIN operations.data_providers p USING (provider_id)
                    WHERE o.observation_key = :key AND o.instrument_id = :instrument AND o.dataset_id = :dataset
                    ORDER BY a.retrieved_at LIMIT 1
                    """).param("key", event.observationKey()).param("instrument", event.instrumentId())
                    .param("dataset", event.datasetId()).query((rs, row) -> new Source(rs.getObject(1, UUID.class),
                            rs.getTimestamp(2).toInstant(), rs.getString(3))).optional()
                    .orElseThrow(() -> new MarketDataValidationException("source artifact is missing for observation"));
            int canonical = jdbc.sql("""
                    INSERT INTO market_data.observations (observation_key, dataset_id, instrument_id, event_type,
                        economic_time, adjustment_mode, payload, source_artifact_id, observed_at)
                    VALUES (:key, :dataset, :instrument, :type, :time, :adjustment, CAST(:payload AS jsonb), :artifact, :observed)
                    ON CONFLICT (observation_key) DO NOTHING
                    """).param("key", event.observationKey()).param("dataset", event.datasetId()).param("instrument", event.instrumentId())
                    .param("type", event.eventType()).param("time", event.economicTime().atOffset(ZoneOffset.UTC))
                    .param("adjustment", event.adjustmentMode()).param("payload", payload).param("artifact", source.id())
                    .param("observed", source.observedAt().atOffset(ZoneOffset.UTC)).update();
            if (canonical == 1 && (event.eventType().equals("DAILY_PRICE") || event.eventType().equals("CORPORATE_ACTION_BATCH"))) {
                dailyData.persist(event,source.id(),source.observedAt());
            } else if (canonical == 1 && java.util.Set.of("FILING_FACTS","FUNDAMENTAL_COLLECTION_STATUS","REGULATORY_FACTS").contains(event.eventType())) {
                fundamentalData.persist(event,source.id(),source.observedAt());
            } else if (canonical == 1) {
                var newest = jdbc.sql("""
                        SELECT observation_key FROM market_data.observations WHERE dataset_id = :dataset
                            AND instrument_id = :instrument AND economic_time = :time AND adjustment_mode = :adjustment
                        ORDER BY observed_at DESC, ingested_at DESC, observation_key LIMIT 1
                        """).param("dataset", event.datasetId()).param("instrument", event.instrumentId())
                        .param("time", event.economicTime().atOffset(ZoneOffset.UTC)).param("adjustment", event.adjustmentMode())
                        .query(String.class).single();
                if (newest.equals(event.observationKey())) {
                    var symbolTime = (bar == null ? source.observedAt() : event.economicTime()).atOffset(ZoneOffset.UTC);
                    var symbol = jdbc.sql("""
                            SELECT symbol FROM reference.instrument_symbols WHERE instrument_id = :instrument
                              AND effective_from <= CAST(:time AS date) AND (effective_to IS NULL OR effective_to > CAST(:time AS date))
                            ORDER BY effective_from DESC LIMIT 1
                            """).param("instrument", event.instrumentId()).param("time", symbolTime).query(String.class).optional()
                            .orElseThrow(() -> new MarketDataValidationException("no effective symbol for observation"));
                    projection.process(new MarketDataEvent(event.eventId(), 1,
                            bar == null ? MarketDataEventType.FUNDAMENTAL_SNAPSHOT : MarketDataEventType.STOCK_BAR,
                            symbol, source.provider(), source.observedAt(), bar, fundamentals));
                }
            }
            return true;
        }));
    }

    public int reconcileCoverage() {
        return transactions.execute(status -> jdbc.sql("SELECT operations.reconcile_ingestion_coverage(:consumer)")
                .param("consumer", consumer).query(Integer.class).single());
    }

    private void lock(String key) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key).query(Integer.class).single();
    }

    private record Receipt(UUID eventId, String key, String hash) { }
    private record Source(UUID id, Instant observedAt, String provider) { }
}
