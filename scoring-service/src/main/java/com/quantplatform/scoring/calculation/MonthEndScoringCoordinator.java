package com.quantplatform.scoring.calculation;

import com.quantplatform.scoring.inputs.ScoringInputRequest;
import com.quantplatform.scoring.runs.ScoringRunService;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Calendar reconciliation for complete frozen-model runs. Dataset identities are explicit configuration. */
@Service
public class MonthEndScoringCoordinator {
    public static final String MODEL="stock-value-quality-momentum:1.1.0";
    private final JdbcClient jdbc;
    private final ScoringRunService runs;
    private final Clock clock;
    private final String rawDataset,adjustedDataset,classification;
    public MonthEndScoringCoordinator(DataSource source,ScoringRunService runs,Clock clock,
            @Value("${scoring.model.raw-dataset:}") String rawDataset,
            @Value("${scoring.model.adjusted-dataset:}") String adjustedDataset,
            @Value("${scoring.model.classification-version:}") String classification){
        jdbc=JdbcClient.create(source);this.runs=runs;this.clock=clock;
        this.rawDataset=rawDataset;this.adjustedDataset=adjustedDataset;this.classification=classification;
    }
    public void reconcile(){
        if(rawDataset.isBlank()||adjustedDataset.isBlank()||classification.isBlank())return;
        for(LocalDate date:dueDates())try{calculate(date);}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Scoring {} will retry: {}",date,failure.getClass().getSimpleName());}
    }
    public List<LocalDate> dueDates(){
        return jdbc.sql("""
            SELECT MAX(session_date) FILTER(WHERE NOT holiday) AS month_end
            FROM reference.trading_sessions WHERE exchange_mic='XNYS'
            GROUP BY date_trunc('month',session_date)
            HAVING COUNT(*)=EXTRACT(day FROM date_trunc('month',MIN(session_date))+INTERVAL '1 month - 1 day')
                AND MAX(closes_at)<=:now
            ORDER BY month_end
            """).param("now",clock.instant().atOffset(ZoneOffset.UTC)).query(LocalDate.class).list();
    }
    public boolean calculate(LocalDate date){
        if(date==null||!dueDates().contains(date))throw new IllegalArgumentException("Score date must be a completed, fully calendared month-end");
        if(rawDataset.isBlank()||adjustedDataset.isBlank()||classification.isBlank())return false;
        Instant cutoff=jdbc.sql("SELECT closes_at FROM reference.trading_sessions WHERE exchange_mic='XNYS' AND session_date=:date")
            .param("date",date).query((rs,n)->rs.getTimestamp(1).toInstant()).single();

        var next=jdbc.sql("""
            SELECT s.opens_at FROM reference.trading_sessions s WHERE s.exchange_mic='XNYS' AND s.session_date>:date AND NOT s.holiday
                AND s.session_date=(SELECT min(session_date) FROM reference.trading_sessions WHERE exchange_mic='XNYS' AND session_date>:date AND NOT holiday)
                AND s.available_at<=:cutoff AND s.observed_at<=:cutoff
                AND (SELECT count(*) FROM reference.trading_sessions c WHERE c.exchange_mic='XNYS'
                    AND c.session_date>:date AND c.session_date<=s.session_date AND c.available_at<=:cutoff AND c.observed_at<=:cutoff)=s.session_date-CAST(:date AS date)
            ORDER BY s.session_date LIMIT 1
            """).param("date",date).param("cutoff",cutoff.atOffset(ZoneOffset.UTC))
            .query((rs,n)->rs.getTimestamp(1).toInstant()).optional();
        if(next.isEmpty())return false;
        Instant decision=next.get().minus(Duration.ofMinutes(30));
        if(clock.instant().isBefore(decision))return false;
        UUID sp=snapshot("SP500",date,decision),nq=snapshot("NASDAQ100",date,decision);if(sp==null||nq==null)return false;
        var request=new ScoringInputRequest(date,cutoff,decision,sp,nq,UUID.fromString(rawDataset),UUID.fromString(adjustedDataset),
            decision.atZone(ZoneId.of("America/New_York")).toLocalDate(),UUID.fromString(classification),"sec-us-gaap-v1",Set.of("TOTAL_ASSETS"),ScoringInputRequest.PRE_OPEN);
        if(!runs.inputsReady(request))return false;
        runs.run(request,next.get(),Map.of());
        return true;
    }
    private UUID snapshot(String code,LocalDate date,Instant cutoff){
        return jdbc.sql("""
            SELECT universe_snapshot_id FROM (
                SELECT s.universe_snapshot_id,s.completeness_status FROM reference.universe_snapshots s
                JOIN reference.universes u USING(universe_id)
                WHERE u.code=:code AND s.effective_date<=:date AND s.observed_at<=:cutoff
                ORDER BY s.effective_date DESC,s.observed_at DESC,s.universe_snapshot_id LIMIT 1
            ) latest WHERE completeness_status='COMPLETE'
            """).param("code",code).param("date",date).param("cutoff",cutoff.atOffset(ZoneOffset.UTC)).query(UUID.class).optional().orElse(null);
    }
}
