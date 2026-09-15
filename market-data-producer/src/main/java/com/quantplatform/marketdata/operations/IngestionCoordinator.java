package com.quantplatform.marketdata.operations;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.marketdata.config.AlpacaProperties;
import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.provider.alpaca.AlpacaStockMarketClient;
import com.quantplatform.marketdata.provider.alphavantage.AlphaVantageFundamentalClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestionCoordinator {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IngestionRunStore store;
    private final TradingCalendarLoader calendar;
    private final AlpacaStockMarketClient alpaca;
    private final AlphaVantageFundamentalClient fundamentals;
    private final MarketDataProperties market;
    private final AlpacaProperties alpacaConfig;
    private final IngestionProperties properties;
    private final Clock clock;
    private final BulkDailyCollector bulk;
    private final DailyPriceProperties prices;
    private com.quantplatform.marketdata.fundamentals.SecFilingsCollector secFilings;
    private com.quantplatform.marketdata.fundamentals.FfiecBulkCollector ffiec;
    @org.springframework.beans.factory.annotation.Autowired
    public void fundamentalCollectors(com.quantplatform.marketdata.fundamentals.SecFilingsCollector sec,
            com.quantplatform.marketdata.fundamentals.FfiecBulkCollector regulatory) {
        this.secFilings=sec;this.ffiec=regulatory;
    }
    private final String worker = "ingestion-" + UUID.randomUUID();
    private volatile Status status = new Status("SYNCING", "Startup reconciliation has not finished", 0, 0, null);

    public IngestionCoordinator(DataSource source, PlatformTransactionManager manager, IngestionRunStore store,
            TradingCalendarLoader calendar, AlpacaStockMarketClient alpaca, AlphaVantageFundamentalClient fundamentals,
            MarketDataProperties market, AlpacaProperties alpacaConfig, IngestionProperties properties, Clock clock, BulkDailyCollector bulk, DailyPriceProperties prices) {
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(manager);
        this.store = store;
        this.calendar = calendar;
        this.alpaca = alpaca;
        this.fundamentals = fundamentals;
        this.market = market;
        this.alpacaConfig = alpacaConfig;
        this.properties = properties;
        this.clock = clock;
        this.bulk = bulk;
        this.prices = prices;
    }

    public Status status() { return status; }
    public void failed(String reason) { status = new Status("FAILED", reason, status.pendingItems(), status.failedItems(), status.completeThrough()); }

    public Status reconcile() {
        status = new Status("SYNCING", "Reconciling desired coverage", status.pendingItems(), status.failedItems(), status.completeThrough());
        if (!market.enabled()) return status = new Status("DEGRADED", "Collection is disabled", 0, 0, null);
        LocalDate today = LocalDate.ofInstant(clock.instant(), NEW_YORK);
        boolean collectPrices=market.latestBarsEnabled()||market.historicalBackfillEnabled();
        LocalDate latestCompleteDay = collectPrices?today.minusDays(1):today;
        LocalDate start = properties.startDate() == null ? firstSupportedDate() : properties.startDate();
        LocalDate through = properties.endDate() == null ? latestCompleteDay : properties.endDate();
        if (through.isAfter(latestCompleteDay)) throw new IllegalArgumentException("daily history must end before the current New York date");
        if (through.isBefore(start)) return status = new Status("DEGRADED", "No completed supported session is due", 0, 0, null);
        if(collectPrices) calendar.ensure(start, today);
        var sessions = collectPrices ? jdbc.sql("""
                SELECT session_date FROM reference.trading_sessions WHERE exchange_mic = 'XNYS'
                    AND NOT holiday AND session_date BETWEEN :start AND :end AND closes_at < :now
                    AND ((session_date + 1)::timestamp AT TIME ZONE 'America/New_York') <= :eligible
                ORDER BY session_date
                """).param("start", start).param("end", through).param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param("eligible", clock.instant().minus(prices.publicationDelay()).atOffset(ZoneOffset.UTC))
                .query(LocalDate.class).list() : List.of(through);
        if (sessions.isEmpty()) return status = new Status("DEGRADED", "No trading sessions in the requested window", 0, 0, null);
        var runIds = new ArrayList<UUID>();
        var work = new LinkedHashMap<UUID, String>();
        if(secFilings!=null && secFilings.enabled()) {
            var config=new LinkedHashMap<>(secFilings.configuration());
            config.put("retrievalDate",today.toString());
            UUID job=register("sec","companyfacts","FILING_FACTS",config);
            UUID run=plan(job,List.of("backfill","force-refresh").contains(properties.mode())?through:today);
            runIds.add(run);work.put(run,"FILING_FACTS");
        }
        if(ffiec!=null && ffiec.enabled()) {
            UUID job=register("ffiec","call-reports","REGULATORY_FACTS",ffiec.configuration());
            UUID run=plan(job,through);runIds.add(run);work.put(run,"REGULATORY_FACTS");
        }
        if (collectPrices) {
            for (String adjustment : prices.adjustedEnabled() ? List.of("RAW","SPLIT_DIVIDEND") : List.of("RAW")) {
                var configuration = new LinkedHashMap<String,Object>();
                configuration.put("feed",prices.feed());
                configuration.put("adjustment",adjustment);
                configuration.put("timeframe","1Day");
                configuration.put("baseUrl",alpacaConfig.baseUrl().toString());
                if (!adjustment.equals("RAW")) configuration.put("adjustmentAsOf",today.toString());
                UUID job = register("alpaca","daily-prices-" + prices.feed() + "-" + adjustment.toLowerCase(java.util.Locale.ROOT),
                        "DAILY_PRICE",configuration);
                for (LocalDate date : sessions) {
                    UUID run = plan(job,date);
                    runIds.add(run);
                    work.put(run,"DAILY_PRICE");
                }
            }
            if (prices.corporateActionsEnabled()) {
                for (LocalDate date = start; !date.isAfter(through); date = date.plusDays(1)) {
                    var configuration = new LinkedHashMap<String,Object>();
                    configuration.put("adjustment","NONE");
                    configuration.put("baseUrl",alpacaConfig.baseUrl().toString());
                    // Provider process dates can arrive late. Revisit recent days with a dated audited run.
                    if (!date.isBefore(today.minusDays(prices.actionsRefreshDays()))) configuration.put("refreshDate",today.toString());
                    UUID job = register("alpaca","corporate-actions","CORPORATE_ACTION_BATCH",configuration);
                    UUID run = plan(job,date);
                    runIds.add(run);
                    work.put(run,"CORPORATE_ACTION_BATCH");
                }
            }
        }
        // OVERVIEW has no historical point-in-time endpoint. Never relabel today's response as an old observation.
        if (market.fundamentalsEnabled() && !List.of("backfill", "force-refresh").contains(properties.mode())) {
            UUID job = register("alpha-vantage", "current-overview", "FUNDAMENTAL_SNAPSHOT", Map.of("function", "OVERVIEW"));
            UUID run = plan(job, today);
            runIds.add(run);
            work.put(run, "FUNDAMENTAL_SNAPSHOT");
        }
        if (runIds.isEmpty()) return status = new Status("DEGRADED", "No collection path is enabled", 0, 0, null);
        reconcileCanonicalCoverage();
        int remaining = properties.itemsPerCycle();
        for (var entry : work.entrySet()) {
            while (remaining > 0) {
                int batchSize = List.of("FUNDAMENTAL_SNAPSHOT","FILING_FACTS","REGULATORY_FACTS").contains(entry.getValue()) ? 1 : Math.min(remaining,prices.symbolsPerRequest());
                var claims = store.claim(entry.getKey(), worker, batchSize, properties.jobLease());
                if (claims.isEmpty()) break;
                remaining -= claims.size();
                if (entry.getValue().equals("FUNDAMENTAL_SNAPSHOT")) process(claims.getFirst(),entry.getValue());
                else if(entry.getValue().equals("FILING_FACTS")) secFilings.collect(claims.getFirst(),market.topic());
                else if(entry.getValue().equals("REGULATORY_FACTS")) ffiec.collect(claims.getFirst(),market.topic());
                else bulk.collect(claims,entry.getValue(),market.topic());
            }
        }
        reconcileCanonicalCoverage();
        var progress = jdbc.sql("""
                SELECT COUNT(*) FILTER (WHERE status <> 'COMPLETE') pending,
                    COUNT(*) FILTER (WHERE status = 'FAILED') failed,
                    COUNT(*) FILTER (WHERE status = 'WAITING_RETRY') retrying
                FROM operations.ingestion_run_items WHERE ingestion_run_id IN (:runs)
                """).param("runs", runIds).query((rs, row) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)}).single();
        long failedDelivery = jdbc.sql("""
                SELECT COUNT(*) FROM operations.outbox_events o JOIN operations.ingestion_item_events e USING (event_id)
                JOIN operations.ingestion_run_items i USING (ingestion_run_item_id)
                WHERE i.ingestion_run_id IN (:runs) AND i.status <> 'COMPLETE' AND o.status = 'FAILED_TERMINAL'
                """).param("runs", runIds).query(Long.class).single();
        LocalDate completeThrough = jdbc.sql("""
                SELECT MAX(r.window_end) FROM operations.ingestion_runs r
                LEFT JOIN reference.trading_sessions s ON s.exchange_mic='XNYS' AND s.session_date=r.window_end AND NOT s.holiday
                WHERE r.ingestion_run_id IN (:runs) AND (:prices=false OR s.session_date IS NOT NULL)
                  AND NOT EXISTS (SELECT 1 FROM operations.ingestion_runs missing
                    WHERE missing.ingestion_run_id IN (:runs) AND missing.window_end<=r.window_end
                      AND (missing.status<>'COMPLETE' OR NOT EXISTS (
                        SELECT 1 FROM operations.data_coverage c WHERE c.ingestion_run_id=missing.ingestion_run_id AND c.valid)))
                """).param("runs", runIds).param("prices",collectPrices).query((rs, row) -> rs.getObject(1, LocalDate.class)).optional().orElse(null);
        long blockingCoverage = jdbc.sql("""
                SELECT COUNT(*) FROM operations.ingestion_runs r WHERE r.ingestion_run_id IN (:runs)
                  AND (EXISTS (SELECT 1 FROM operations.data_quality_issues q WHERE q.dataset_id = r.dataset_id
                    AND q.status = 'OPEN' AND q.severity = 'BLOCKING'
                    AND (q.ingestion_run_id IS NULL OR q.ingestion_run_id = r.ingestion_run_id))
                    OR EXISTS (SELECT 1 FROM operations.data_coverage c
                        WHERE c.ingestion_run_id = r.ingestion_run_id AND NOT c.valid))
                """).param("runs", runIds).query(Long.class).single();
        String state = progress[1] > 0 || failedDelivery > 0 ? "FAILED"
                : progress[2] > 0 || blockingCoverage > 0 ? "DEGRADED" : progress[0] > 0 ? "SYNCING" : "READY";
        return status = new Status(state, state.equals("READY") ? "Required observations are durably accepted"
                : "Inspect persisted ingestion attempts and delivery state", progress[0], progress[1] + failedDelivery, completeThrough, collectPrices?sessions.getLast():null, prices.feed(), collectPrices&&prices.feed().equals("sip"));
    }

    private void process(JobLease lease, String type) {
        try {
            var request = jdbc.sql("""
                    SELECT r.window_start, s.symbol FROM operations.ingestion_runs r
                    JOIN reference.instrument_symbols s ON s.instrument_id = :instrument
                        AND s.effective_from <= r.window_start AND (s.effective_to IS NULL OR s.effective_to > r.window_start)
                    WHERE r.ingestion_run_id = :run ORDER BY s.effective_from DESC LIMIT 1
                    """).param("instrument", lease.instrumentId()).param("run", lease.runId())
                    .query((rs, row) -> new Request(rs.getObject(1, LocalDate.class), rs.getString(2))).optional()
                    .orElseThrow(() -> new IllegalStateException("NO_EFFECTIVE_SYMBOL"));
            if (type.equals("STOCK_BAR")) collectBarPage(lease, request);
            else collectOverview(lease, request);
        } catch (RuntimeException failure) {
            long multiplier = 1L << Math.min(lease.attemptNumber() - 1, 8);
            try {
                store.retry(lease, "PROVIDER_OR_VALIDATION_FAILURE", failure.getClass().getSimpleName(),
                        properties.retryBackoff().multipliedBy(multiplier));
            } catch (IllegalStateException expired) {
                // Another worker can resume an expired lease; never overwrite its state.
            }
        }
    }

    private void collectBarPage(JobLease lease, Request request) {
        var checkpoint = lease.checkpointJson() == null ? Map.<String, Object>of() : CanonicalJson.readObject(lease.checkpointJson());
        String pageToken = checkpoint.getOrDefault("nextPageToken", "").toString();
        List<?> previous = (List<?>) checkpoint.getOrDefault("seenPageTokens", List.of());
        var seen = new ArrayList<>(previous.stream().map(Object::toString).toList());
        int total = ((Number) checkpoint.getOrDefault("acceptedProviderBars", 0)).intValue();
        while (true) {
            store.renew(lease, properties.jobLease());
            var page = alpaca.fetchDurablePage(request.symbol(), request.date().atStartOfDay(NEW_YORK).toInstant(),
                    request.date().plusDays(1).atStartOfDay(NEW_YORK).toInstant().minusNanos(1), pageToken);
            for (var bar : page.bars()) {
                if (!LocalDate.ofInstant(bar.time(), NEW_YORK).equals(request.date())) {
                    throw new IllegalArgumentException("provider returned a bar outside the requested day");
                }
            }
            String next = page.nextPageToken() == null ? "" : page.nextPageToken();
            if (!next.isBlank() && (seen.contains(next) || seen.size() >= 1000)) {
                throw new IllegalArgumentException("provider repeated/exceeded pagination tokens");
            }
            if (!next.isBlank()) seen.add(next);
            total += page.bars().size();
            store.stage(lease, new SourceArtifact(page.sourceUri(), page.sourceUri(), page.retrievedAt(), "alpaca-bar-v1", page.rawJson()),
                    page.bars().stream().map(bar -> new OutboxObservation(market.topic(), "STOCK_BAR", bar.time(), "RAW",
                            CanonicalJson.MAPPER.writeValueAsString(bar))).toList(),
                    CanonicalJson.write(Map.of("nextPageToken", next, "seenPageTokens", seen, "acceptedProviderBars", total)),
                    next.isBlank() && total > 0);
            if (next.isBlank()) {
                if (total == 0) throw new IllegalStateException("NO_BAR_FOR_EXPECTED_SESSION");
                return;
            }
            pageToken = next;
        }
    }

    private void collectOverview(JobLease lease, Request request) {
        var response = fundamentals.fetchDurableOverview(request.symbol());
        var period = response.snapshot().latestQuarter();
        Instant economicTime = period == null ? Instant.EPOCH : period.atStartOfDay(ZoneOffset.UTC).toInstant();
        store.stage(lease, new SourceArtifact(request.date() + ":" + request.symbol(), response.sourceUri(), response.retrievedAt(),
                        "alpha-overview-v1", response.rawJson()),
                List.of(new OutboxObservation(market.topic(), "FUNDAMENTAL_SNAPSHOT", economicTime, "NONE",
                        CanonicalJson.MAPPER.writeValueAsString(response.snapshot()))), "{}", true);
    }

    private UUID plan(UUID job, LocalDate date) {
        String requestKey = properties.mode().equals("force-refresh") ? "force:" + properties.requestId() + ":" + properties.reason() : "scheduled";
        return store.plan(new IngestionPlan(job, snapshot("SP500", date), snapshot("NASDAQ100", date), date, date,
                properties.mode(), requestKey, "phase4-v1"));
    }

    private UUID snapshot(String code, LocalDate date) {
        return jdbc.sql("""
                SELECT s.universe_snapshot_id FROM reference.universe_snapshots s JOIN reference.universes u USING (universe_id)
                WHERE u.code = :code AND s.completeness_status = 'COMPLETE' AND s.effective_date <= :date AND s.observed_at <= :now
                ORDER BY s.effective_date DESC, s.observed_at DESC, s.universe_snapshot_id LIMIT 1
                """).param("code", code).param("date", date).param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query(UUID.class).optional().orElseThrow(() -> new IllegalStateException("No complete " + code + " snapshot for " + date));
    }

    private LocalDate firstSupportedDate() {
        return jdbc.sql("""
                SELECT MAX(first_date) FROM (SELECT u.code, MIN(s.effective_date) first_date
                    FROM reference.universes u JOIN reference.universe_snapshots s USING (universe_id)
                    WHERE u.code IN ('SP500','NASDAQ100') AND s.completeness_status = 'COMPLETE'
                    GROUP BY u.code) dates HAVING COUNT(*) = 2
                """).query(LocalDate.class).optional().orElseThrow(() -> new IllegalStateException("Import both universe snapshots before collecting data"));
    }

    private UUID register(String providerCode, String datasetCode, String eventType, Map<String, Object> configuration) {
        return transactions.execute(status -> {
            UUID provider = jdbc.sql("""
                    INSERT INTO operations.data_providers (code, name, license_notes) VALUES (:code, :code,
                        'Development adapter; account permissions and provider terms govern use')
                    ON CONFLICT (code) DO UPDATE SET code = EXCLUDED.code RETURNING provider_id
                    """).param("code", providerCode).query(UUID.class).single();
            UUID dataset = jdbc.sql("""
                    INSERT INTO operations.datasets (provider_id, code, version, license_notes)
                    VALUES (:provider, :code, '1', 'Raw development observations; not a licensed historical research dataset')
                    ON CONFLICT (provider_id, code, version) DO UPDATE SET code = EXCLUDED.code RETURNING dataset_id
                    """).param("provider", provider).param("code", datasetCode).query(UUID.class).single();
            var config = new LinkedHashMap<>(configuration);
            config.put("eventType", eventType);
            config.put("topic", market.topic());
            config.put("maxAttempts", properties.maxAttempts());
            String json = CanonicalJson.write(config);
            return jdbc.sql("""
                    INSERT INTO operations.job_definitions (dataset_id, code, version, configuration, max_attempts)
                    VALUES (:dataset, :code, :version, CAST(:config AS jsonb), :max)
                    ON CONFLICT (code, version) DO UPDATE SET code = EXCLUDED.code RETURNING job_definition_id
                    """).param("dataset", dataset).param("code", providerCode + ":" + datasetCode)
                    .param("version", CanonicalJson.sha256(json).substring(0, 40)).param("config", json)
                    .param("max", properties.maxAttempts()).query(UUID.class).single();
        });
    }

    private void reconcileCanonicalCoverage() {
        jdbc.sql("SELECT operations.reconcile_ingestion_coverage(:consumer)").param("consumer", properties.canonicalConsumer())
                .query(Integer.class).single();
    }

    public record Status(String state, String detail, long pendingItems, long failedItems, LocalDate completeThrough,
                         LocalDate latestEligibleSession, String feed, boolean consolidatedLiquidityEligible) {
        public Status(String state, String detail, long pendingItems, long failedItems, LocalDate completeThrough) {
            this(state,detail,pendingItems,failedItems,completeThrough,null,null,false);
        }
    }
    private record Request(LocalDate date, String symbol) { }
}
