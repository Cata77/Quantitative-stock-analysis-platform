package com.quantplatform.scoring.calculation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Phase 3 scheduling guard around the existing demonstration formula, pending the Phase 7/8 model. */
@Service
public class MonthEndScoringCoordinator {
    public static final String MODEL = "legacy-equal-weight-v1";
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final FactorScoringService calculator;
    private final Clock clock;

    public MonthEndScoringCoordinator(DataSource source, PlatformTransactionManager manager, FactorScoringService calculator, Clock clock) {
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(manager);
        this.calculator = calculator;
        this.clock = clock;
    }

    public void reconcile() {
        for (LocalDate date : dueDates()) calculate(date);
    }

    public List<LocalDate> dueDates() {
        return jdbc.sql("""
                SELECT MAX(session_date) FILTER (WHERE NOT holiday) AS month_end
                FROM reference.trading_sessions WHERE exchange_mic = 'XNYS'
                GROUP BY date_trunc('month', session_date)
                HAVING COUNT(*) = EXTRACT(day FROM date_trunc('month', MIN(session_date)) + INTERVAL '1 month - 1 day')
                    AND MAX(closes_at) <= :now
                ORDER BY month_end
                """).param("now", clock.instant().atOffset(ZoneOffset.UTC)).query(LocalDate.class).list();
    }

    public boolean calculate(LocalDate date) {
        if (date == null || !dueDates().contains(date)) throw new IllegalArgumentException("score date must be a completed, fully calendared month-end");
        var sp500 = snapshot("SP500", date);
        var nasdaq = snapshot("NASDAQ100", date);
        if (sp500 == null || nasdaq == null) return false;
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            Instant cutoff = jdbc.sql("SELECT closes_at FROM reference.trading_sessions WHERE exchange_mic = 'XNYS' AND session_date = :date")
                    .param("date", date).query((rs, row) -> rs.getTimestamp(1).toInstant()).single();
            jdbc.sql("""
                    INSERT INTO operations.month_end_score_jobs (as_of_date, cutoff, model_code, sp500_snapshot_id, nasdaq100_snapshot_id)
                    VALUES (:date, :cutoff, :model, :sp500, :nasdaq) ON CONFLICT DO NOTHING
                    """).param("date", date).param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).param("model", MODEL)
                    .param("sp500", sp500).param("nasdaq", nasdaq).update();
            var job = jdbc.sql("""
                    SELECT score_job_id, status FROM operations.month_end_score_jobs
                    WHERE as_of_date = :date AND model_code = :model AND sp500_snapshot_id = :sp500 AND nasdaq100_snapshot_id = :nasdaq
                    FOR UPDATE
                    """).param("date", date).param("model", MODEL).param("sp500", sp500).param("nasdaq", nasdaq)
                    .query((rs, row) -> new Job(rs.getObject(1, UUID.class), rs.getString(2))).single();
            if (job.status().equals("PUBLISHED")) return true;
            boolean covered = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM operations.data_coverage c
                        JOIN operations.ingestion_runs r USING (ingestion_run_id)
                        JOIN operations.job_definitions j USING (job_definition_id)
                        WHERE c.boundary_date = :date AND c.valid AND r.status = 'COMPLETE'
                          AND j.configuration ->> 'eventType' = 'STOCK_BAR'
                          AND EXISTS (SELECT 1 FROM operations.ingestion_run_snapshots s
                              WHERE s.ingestion_run_id = r.ingestion_run_id AND s.universe_snapshot_id = :sp500)
                          AND EXISTS (SELECT 1 FROM operations.ingestion_run_snapshots s
                              WHERE s.ingestion_run_id = r.ingestion_run_id AND s.universe_snapshot_id = :nasdaq))
                    """).param("date", date).param("sp500", sp500).param("nasdaq", nasdaq).query(Boolean.class).single();
            if (!covered) return false;
            var instruments = jdbc.sql("SELECT instrument_id FROM reference.research_universe_for_snapshots(:sp500, :nasdaq) WHERE primary_liquid_class")
                    .param("sp500", sp500).param("nasdaq", nasdaq).query(UUID.class).list();
            var symbols = jdbc.sql("""
                    SELECT symbol FROM reference.instrument_symbols WHERE instrument_id IN (:instruments)
                        AND effective_from <= :date AND (effective_to IS NULL OR effective_to > :date)
                    ORDER BY instrument_id
                    """).param("instruments", instruments).param("date", date).query(String.class).list();
            if (symbols.size() != instruments.size()) return false;
            var scores = calculator.calculateAt(cutoff, symbols);
            if (scores.isEmpty()) return false;
            jdbc.sql("""
                    UPDATE operations.month_end_score_jobs SET status = 'PUBLISHED', scored_count = :count,
                        published_at = clock_timestamp() WHERE score_job_id = :id
                    """).param("count", scores.size()).param("id", job.id()).update();
            return true;
        }));
    }

    private UUID snapshot(String code, LocalDate date) {
        return jdbc.sql("""
                SELECT s.universe_snapshot_id FROM reference.universe_snapshots s JOIN reference.universes u USING (universe_id)
                WHERE u.code = :code AND s.completeness_status = 'COMPLETE' AND s.effective_date <= :date
                ORDER BY s.effective_date DESC, s.observed_at DESC, s.universe_snapshot_id LIMIT 1
                """).param("code", code).param("date", date).query(UUID.class).optional().orElse(null);
    }

    private record Job(UUID id, String status) { }
}
