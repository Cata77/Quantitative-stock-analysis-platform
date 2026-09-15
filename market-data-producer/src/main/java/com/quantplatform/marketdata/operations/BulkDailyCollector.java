package com.quantplatform.marketdata.operations;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.marketdata.provider.alpaca.AlpacaDailyDataClient;
import com.quantplatform.marketdata.provider.alpaca.ProviderRequestGate;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BulkDailyCollector {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final IngestionRunStore store;
    private final AlpacaDailyDataClient client;
    private final IngestionProperties properties;
    public BulkDailyCollector(DataSource source, PlatformTransactionManager manager, IngestionRunStore store,
            AlpacaDailyDataClient client, IngestionProperties properties) {
        jdbc = JdbcClient.create(source);
        tx = new TransactionTemplate(manager);
        this.store = store;
        this.client = client;
        this.properties = properties;
    }

    public void collect(List<JobLease> leases, String type, String topic) {
        // Resumed pages retain their exact request symbol set, even if only a subset needs retry.
        var groups = new LinkedHashMap<String,List<JobLease>>();
        for (var lease : leases) {
            var checkpoint = checkpoint(lease);
            String group = checkpoint.containsKey("requestSymbols") ? CanonicalJson.write(checkpoint.get("requestSymbols")) : "new";
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(lease);
        }
        for (var group : groups.values()) {
            try { collectGroup(group, type, topic); }
            catch (RuntimeException failure) {
                for (var lease : group) {
                    try { store.retry(lease, failure instanceof ProviderRequestGate.DeferredRequest ? "PROVIDER_RATE_LIMIT" : "BULK_REQUEST_FAILED",
                            failure.getClass().getSimpleName(), failure instanceof ProviderRequestGate.DeferredRequest deferred ? deferred.delay()
                                    : properties.retryBackoff().multipliedBy(1L << Math.min(lease.attemptNumber()-1,8))); }
                    catch (IllegalStateException expired) { /* A replaced lease belongs to another worker. */ }
                }
            }
        }
    }

    private void collectGroup(List<JobLease> group, String type, String topic) {
        var run = jdbc.sql("""
                SELECT r.window_start,j.configuration::text FROM operations.ingestion_runs r
                JOIN operations.job_definitions j USING (job_definition_id) WHERE r.ingestion_run_id=:id
                """).param("id", group.getFirst().runId()).query((rs,row) ->
                new Run(rs.getObject(1,LocalDate.class),CanonicalJson.readObject(rs.getString(2)))).single();
        String adjustment = run.config().getOrDefault("adjustment","NONE").toString();
        LocalDate vintage = run.config().containsKey("adjustmentAsOf") ? LocalDate.parse(run.config().get("adjustmentAsOf").toString()) : null;
        var symbols = new TreeMap<UUID,String>();
        var aliases = new TreeMap<UUID,List<String>>();
        for (var lease : group) {
            // All aliases overlapping the day are used for actions; bar requests use that day's effective identity.
            var symbol = jdbc.sql("""
                    SELECT symbol FROM reference.instrument_symbols WHERE instrument_id=:id
                    AND effective_from<=:date AND (effective_to IS NULL OR effective_to>:date)
                    ORDER BY effective_from DESC LIMIT 1
                    """).param("id",lease.instrumentId()).param("date",run.date()).query(String.class).optional()
                    .orElseThrow(() -> new IllegalArgumentException("NO_EFFECTIVE_SYMBOL"));
            symbols.put(lease.instrumentId(),symbol);
            aliases.put(lease.instrumentId(), type.equals("DAILY_PRICE") ? List.of(symbol) : jdbc.sql("""
                    SELECT DISTINCT symbol FROM reference.instrument_symbols WHERE instrument_id=:id
                    AND effective_from<=:date AND (effective_to IS NULL OR effective_to>=:date)
                    ORDER BY symbol
                    """).param("id",lease.instrumentId()).param("date",run.date()).query(String.class).list());
        }
        var firstCheckpoint = checkpoint(group.getFirst());
        List<String> requestSymbols = firstCheckpoint.containsKey("requestSymbols")
                ? ((List<?>)firstCheckpoint.get("requestSymbols")).stream().map(Object::toString).toList()
                : aliases.values().stream().flatMap(List::stream).distinct().sorted().toList();
        // Different page tokens can occur when a previous partial retry was interrupted. Resume separately.
        var byPage = new LinkedHashMap<String,List<JobLease>>();
        for (var lease : group) byPage.computeIfAbsent(Objects.toString(checkpoint(lease).get("nextPageToken"),""), ignored->new ArrayList<>()).add(lease);
        for (var samePage : byPage.entrySet()) {
            String token = samePage.getKey();
            var active = samePage.getValue();
            var hashes = new HashMap<UUID,String>();
            active.forEach(lease -> hashes.put(lease.itemId(), Objects.toString(checkpoint(lease).get("barHash"), "")));
            var counts = new HashMap<UUID,Integer>();
            active.forEach(lease -> counts.put(lease.itemId(), ((Number)checkpoint(lease).getOrDefault("barsSeen",0)).intValue()));
            var seen = new ArrayList<String>();
            Object savedTokens = checkpoint(active.getFirst()).get("seenPageTokens");
            if (savedTokens instanceof List<?> list) list.forEach(value -> seen.add(value.toString()));
            while (true) {
                active.forEach(lease -> store.renew(lease,properties.jobLease()));
                var page = type.equals("DAILY_PRICE") ? client.bars(requestSymbols,run.date(),adjustment,vintage,token)
                        : client.actions(requestSymbols,run.date(),token);
                String next = Objects.toString(page.nextPageToken(),"");
                if (!next.isBlank() && (seen.contains(next) || seen.size() >= 1000)) throw new IllegalArgumentException("repeated/excessive page token");
                if (!next.isBlank()) seen.add(next);
                var tokens = List.copyOf(seen);
                tx.executeWithoutResult(status -> {
                    // Stable lock ordering makes page staging atomic across every claimed instrument.
                    for (var lease : active.stream().sorted(Comparator.comparing(JobLease::itemId)).toList()) {
                        var values = aliases.get(lease.instrumentId()).stream()
                                .flatMap(alias -> page.observations().getOrDefault(alias,List.of()).stream()).distinct().toList();
                        var events = new ArrayList<OutboxObservation>();
                        if (type.equals("DAILY_PRICE")) {
                            for (var value : values) {
                                String hash = CanonicalJson.sha256(CanonicalJson.write(value));
                                String previous = hashes.get(lease.itemId());
                                if (!previous.isBlank() && !previous.equals(hash))
                                    throw new IllegalArgumentException("conflicting daily bars within one paginated request");
                                hashes.put(lease.itemId(), hash);
                                counts.put(lease.itemId(), 1);
                                events.add(new OutboxObservation(topic,type,Instant.parse(value.get("time").toString()),
                                        adjustment,CanonicalJson.write(value)));
                            }
                        } else {
                            var payload = Map.of("processDate",run.date().toString(),"actions",values);
                            events.add(new OutboxObservation(topic,"CORPORATE_ACTION_BATCH",run.date().atStartOfDay(ZoneOffset.UTC).toInstant(),
                                    "NONE",CanonicalJson.write(payload)));
                        }
                        boolean missing = type.equals("DAILY_PRICE") && next.isBlank() && counts.get(lease.itemId()) == 0;
                        store.stage(lease,new SourceArtifact(page.sourceUri(),page.sourceUri(),page.retrievedAt(),"alpaca-daily-v2",page.rawJson()),
                                events,CanonicalJson.write(Map.of("requestSymbols",requestSymbols,"nextPageToken",next,
                                        "seenPageTokens",next.isBlank() ? List.of() : tokens,"barsSeen",counts.getOrDefault(lease.itemId(),0),"barHash",hashes.getOrDefault(lease.itemId(),""))),
                                next.isBlank() && !missing);
                        if (missing) {
                            gap(lease,run.date());
                            store.retry(lease,"MISSING_SESSION","Expected daily bar was absent from all provider pages",properties.retryBackoff());
                        } else if (next.isBlank()) {
                            jdbc.sql("""
                                    UPDATE operations.data_quality_issues SET status='RESOLVED',resolved_at=clock_timestamp()
                                    WHERE ingestion_run_id=:run AND instrument_id=:instrument
                                        AND issue_type='MISSING_SESSION' AND status='OPEN'
                                    """).param("run",lease.runId()).param("instrument",lease.instrumentId()).update();
                        }
                    }
                });
                if (next.isBlank()) break;
                token = next;
            }
        }
    }
    private void gap(JobLease lease, LocalDate date) {
        jdbc.sql("""
                INSERT INTO operations.data_quality_issues (dataset_id,ingestion_run_id,instrument_id,severity,issue_type,affected_key,evidence)
                SELECT dataset_id,:run,:instrument,'BLOCKING','MISSING_SESSION',:date,'{"reason":"provider pagination exhausted"}'
                FROM operations.ingestion_runs WHERE ingestion_run_id=:run
                ON CONFLICT DO NOTHING
                """).param("run",lease.runId()).param("instrument",lease.instrumentId()).param("date",date.toString()).update();
    }
    private Map<String,Object> checkpoint(JobLease lease) {
        return lease.checkpointJson()==null ? Map.of() : CanonicalJson.readObject(lease.checkpointJson());
    }
    private record Run(LocalDate date, Map<String,Object> config) { }
}
