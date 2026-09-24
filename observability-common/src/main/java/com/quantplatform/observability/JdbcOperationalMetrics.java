package com.quantplatform.observability;

import java.util.*;
import javax.sql.DataSource;
import io.micrometer.core.instrument.*;
import org.springframework.scheduling.annotation.Scheduled;

/** Poll durable state off the scrape thread. Missing data is NaN, never healthy zero. */
public final class JdbcOperationalMetrics {
    private final DataSource source;
    private final Map<String,String> queries=new LinkedHashMap<>();
    private volatile Map<String,Double> snapshot=Map.of();
    private volatile double up=0;
    public JdbcOperationalMetrics(DataSource source,MeterRegistry registry,String application) {
        this.source=source;
        boolean ingestion=application.equals("market-data-producer");
        boolean scoring=application.equals("scoring-service");
        if(ingestion||scoring) {
            queries.put("outbox.pending","SELECT count(*) FROM operations.outbox_events WHERE status<>'PUBLISHED'");
            queries.put("outbox.oldest.seconds","SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(created_at)),0) FROM operations.outbox_events WHERE status<>'PUBLISHED'");
            queries.put("inbox.accepted","SELECT count(*) FROM operations.kafka_inbox WHERE processing_result='ACCEPTED'");
            queries.put("inbox.redeliveries","SELECT COALESCE(sum(delivery_count-1),0) FROM operations.kafka_inbox");
            queries.put("canonical.pending","SELECT count(*) FROM operations.ingestion_run_items WHERE status='STAGED'");
            queries.put("ingestion.retries","SELECT COALESCE(sum(GREATEST(attempt_count-1,0)),0) FROM operations.ingestion_run_items");
            queries.put("ingestion.duration.seconds","SELECT EXTRACT(EPOCH FROM completed_at-started_at) FROM operations.ingestion_runs WHERE status='COMPLETE' ORDER BY completed_at DESC LIMIT 1");
            queries.put("watermark.lag.seconds","SELECT EXTRACT(EPOCH FROM now()-min(complete_through)::timestamptz) FROM operations.data_watermarks");
            queries.put("quality.blocking","SELECT count(*) FROM operations.data_quality_issues WHERE status='OPEN' AND severity='BLOCKING'");
            queries.put("dlq.unreplayed","SELECT count(*) FROM operations.dead_letter_records d WHERE NOT EXISTS (SELECT 1 FROM operations.dead_letter_replays r WHERE r.dead_letter_id=d.dead_letter_id AND r.status='PUBLISHED')");
        }
        if(scoring||application.equals("screener-service")) {
            String latest="WITH latest AS (SELECT * FROM research.scoring_runs WHERE state='PUBLISHED' AND candidate='primary' ORDER BY as_of_date DESC,published_at DESC,score_run_id LIMIT 1) ";
            queries.put("publication.present",latest+"SELECT count(*) FROM latest");
            queries.put("publication.timestamp",latest+"SELECT EXTRACT(EPOCH FROM published_at) FROM latest");
            queries.put("publication.age.seconds",latest+"SELECT EXTRACT(EPOCH FROM now()-market_cutoff) FROM latest");
            queries.put("universe.coverage",latest+"SELECT scored_count::double precision/NULLIF(expected_count,0) FROM latest");
            queries.put("exclusions",latest+"SELECT excluded_count FROM latest");
            queries.put("scoring.duration.seconds",latest+"SELECT EXTRACT(EPOCH FROM finished_at-started_at) FROM latest");
            queries.put("scoring.retries","SELECT COALESCE(sum(GREATEST(attempts-1,0)),0) FROM research.scoring_runs");
            for(String profile:List.of("GENERAL","BANK","PC_INSURER","LIFE_INSURER","EQUITY_REIT"))
                queries.put("profile.coverage."+profile.toLowerCase(Locale.ROOT),latest+
                    "SELECT count(*) FILTER(WHERE eligible)::double precision/NULLIF(count(*),0) FROM research.stock_scores WHERE score_run_id=(SELECT score_run_id FROM latest) AND scoring_profile='"+profile+"'");
        }
        queries.keySet().forEach(name -> Gauge.builder("quant."+name,this,m -> m.snapshot.getOrDefault(name,Double.NaN)).register(registry));
        Gauge.builder("quant.telemetry.up",this,m -> m.up).register(registry);
    }
    @Scheduled(fixedDelayString="${operations.telemetry.interval-ms:30000}")
    public void refresh() {
        var next=new HashMap<String,Double>();
        try(var connection=source.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(5);
                for(var entry:queries.entrySet()) {
                    try(var rows=statement.executeQuery(entry.getValue())) {
                        double value=Double.NaN;
                        if(rows.next()) { value=rows.getDouble(1); if(rows.wasNull())value=Double.NaN; }
                        next.put(entry.getKey(),value);
                    }
                }
            }
            connection.commit(); snapshot=Map.copyOf(next); up=1;
        } catch(Exception failure) { snapshot=Map.of(); up=0; }
    }
}
