package com.quantplatform.scoring.runs;

import static com.quantplatform.scoring.model.FrozenModel.*;
import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.scoring.inputs.*;
import com.quantplatform.scoring.model.*;
import java.math.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Frozen inputs survive failed attempts; output batches and publication share a locked transaction. */
@Service
public class ScoringRunService {
    private final JdbcClient jdbc;
    private final JdbcTemplate batches;
    private final TransactionTemplate tx;
    private final ScoringInputRepository inputs;
    private final FrozenModel model=new FrozenModel();
    private final String certification=GoldenParity.verify(model);
    public ScoringRunService(DataSource source,PlatformTransactionManager manager,ScoringInputRepository inputs){
        jdbc=JdbcClient.create(source);batches=new JdbcTemplate(source);tx=new TransactionTemplate(manager);this.inputs=inputs;
        tx.setTimeout(180);
    }
    public boolean inputsReady(ScoringInputRequest request){
        return jdbc.sql("""
            SELECT NOT EXISTS (
                SELECT 1 FROM (VALUES(CAST(:raw AS uuid),'RAW'),(CAST(:adjusted AS uuid),'SPLIT_DIVIDEND')) required(dataset,mode)
                WHERE NOT EXISTS (
                    SELECT 1 FROM operations.data_coverage c JOIN operations.ingestion_runs r USING(ingestion_run_id)
                    JOIN operations.job_definitions j USING(job_definition_id)
                    WHERE c.dataset_id=required.dataset AND c.boundary_date=:date AND c.valid AND r.status='COMPLETE'
                        AND j.configuration->>'eventType'='DAILY_PRICE' AND j.configuration->>'feed'='sip'
                        AND j.configuration->>'adjustment'=required.mode
                        AND (required.mode='RAW' OR j.configuration->>'adjustmentAsOf'=CAST(:basis AS text))
                        AND EXISTS(SELECT 1 FROM operations.ingestion_run_snapshots s WHERE s.ingestion_run_id=r.ingestion_run_id AND s.universe_snapshot_id=:sp)
                        AND EXISTS(SELECT 1 FROM operations.ingestion_run_snapshots s WHERE s.ingestion_run_id=r.ingestion_run_id AND s.universe_snapshot_id=:nq)
                )
            )
            """).param("raw",request.rawDataset()).param("adjusted",request.adjustedDataset()).param("date",request.scoreDate())
            .param("basis",request.adjustmentBasis().toString()).param("sp",request.sp500Snapshot()).param("nq",request.nasdaq100Snapshot())
            .query(Boolean.class).single();
    }

    public UUID run(ScoringInputRequest request,Instant effectiveFrom,Map<String,String> jurisdictions){
        var requestMap=CanonicalJson.readObject(CanonicalJson.MAPPER.writeValueAsString(request));
        requestMap.put("requiredMetrics",request.requiredMetrics().stream().sorted().toList());
        String requestJson=CanonicalJson.write(obj("inputs",requestMap,
            "jurisdictions",new TreeMap<>(jurisdictions),"candidate","primary","effective_from",effectiveFrom.toString()));
        String key=CanonicalJson.hash(FrozenModel.SHA,requestJson);
        UUID id=tx.execute(status->{
            UUID modelId=jdbc.sql("SELECT model_version_id FROM research.model_versions WHERE manifest_sha256=:hash AND approval_state <> 'RETIRED'")
                .param("hash",FrozenModel.SHA).query(UUID.class).single();
            jdbc.sql("""
                INSERT INTO research.model_parity_certifications(certification_id,model_version_id,manifest_sha256,implementation_version,absolute_tolerance)
                VALUES(:cert,:model,:sha,'java-frozen-v1',0.000000001) ON CONFLICT DO NOTHING
                """).param("cert",certification).param("model",modelId).param("sha",FrozenModel.SHA).update();
            jdbc.sql("""
                INSERT INTO research.scoring_runs(logical_key,as_of_date,market_cutoff,knowledge_cutoff,effective_from,
                    sp500_snapshot_id,nasdaq100_snapshot_id,model_version_id,certification_id,candidate,input_version,request,supersedes)
                VALUES(:key,:date,:market,:knowledge,:effective,:sp,:nq,:model,:cert,'primary','prepared-canonical-v2',CAST(:request AS jsonb),
                    (SELECT score_run_id FROM research.scoring_runs WHERE as_of_date=:date AND model_version_id=:model AND candidate='primary' AND state='PUBLISHED' ORDER BY published_at DESC,score_run_id LIMIT 1))
                ON CONFLICT(logical_key) DO NOTHING
                """).param("key",key).param("date",request.scoreDate()).param("market",request.marketCutoff().atOffset(ZoneOffset.UTC))
                .param("knowledge",request.knowledgeCutoff().atOffset(ZoneOffset.UTC)).param("effective",effectiveFrom.atOffset(ZoneOffset.UTC))
                .param("sp",request.sp500Snapshot()).param("nq",request.nasdaq100Snapshot()).param("model",modelId).param("cert",certification).param("request",requestJson).update();
            return jdbc.sql("SELECT score_run_id FROM research.scoring_runs WHERE logical_key=:key").param("key",key).query(UUID.class).single();
        });
        try {
            // Persist the attempt before work. A crashed RUNNING attempt is immediately retryable:
            // PostgreSQL releases its row lock when the dead connection closes.
            tx.executeWithoutResult(status->{
                var run=lock(id);if("PUBLISHED".equals(run.get("state")))return;
                jdbc.sql("UPDATE research.scoring_runs SET state='RUNNING', attempts=attempts+1,started_at=clock_timestamp(),finished_at=NULL,failure_reason=NULL WHERE score_run_id=:id").param("id",id).update();
            });
            tx.executeWithoutResult(status->{
                var run=lock(id);if("PUBLISHED".equals(run.get("state"))||run.get("input_document")!=null)return;
                if(!inputsReady(request))throw new IllegalStateException("WAITING_FOR_DAILY_COVERAGE");
                var section=inputs.load(request);
                var prepared=CanonicalModelInputs.prepare(section,jurisdictions);
                String document=CanonicalJson.write(obj("canonical",CanonicalJson.readObject(CanonicalJson.MAPPER.writeValueAsString(section)),"prepared",prepared));
                jdbc.sql("UPDATE research.scoring_runs SET input_document=:document,input_sha256=:hash,expected_count=:count WHERE score_run_id=:id")
                    .param("document",document).param("hash",CanonicalJson.sha256(document)).param("count",section.inputs().size()).param("id",id).update();
            });
            tx.executeWithoutResult(status->{
                var run=lock(id);if("PUBLISHED".equals(run.get("state")))return;
                var document=CanonicalJson.readObject((String)run.get("input_document"));var prepared=map(document.get("prepared"));
                var report=model.score(list(prepared.get("rows")).stream().map(FrozenModel::map).toList(),(String)prepared.get("as_of"),"primary");
                if(!"COMPLETE".equals(report.get("status")))throw new IllegalStateException("INSUFFICIENT_UNIVERSE");
                persist(id,document,report);
                beforePublication(id);
                jdbc.sql("""
                    UPDATE research.scoring_runs SET state='PUBLISHED',certification_id=:cert,eligible_count=:scored,scored_count=:scored,
                        excluded_count=:excluded,finished_at=clock_timestamp(),published_at=clock_timestamp(),failure_reason=NULL
                    WHERE score_run_id=:id
                    """).param("cert",certification).param("scored",report.get("eligible_count")).param("excluded",report.get("excluded_count")).param("id",id).update();
            });
            org.slf4j.LoggerFactory.getLogger(getClass()).atInfo().addKeyValue("run_id",id)
                .addKeyValue("score_date",request.scoreDate()).log("Complete scoring publication available");
            return id;
        }catch(RuntimeException failure){
            tx.executeWithoutResult(status->{
                lock(id);
                jdbc.sql("UPDATE research.scoring_runs SET state='FAILED',finished_at=clock_timestamp(),failure_reason=:reason WHERE score_run_id=:id AND state<>'PUBLISHED'")
                    .param("id",id).param("reason",failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()).substring(0,Math.min(1500,String.valueOf(failure.getMessage()).length()))).update();
            });
            throw failure;
        }
    }
    /** Fault-injection seam for verifying rollback after all batches but before publication. */
    protected void beforePublication(UUID id){}
    private Map<String,Object> lock(UUID id){
        return jdbc.sql("SELECT state,input_document FROM research.scoring_runs WHERE score_run_id=:id FOR UPDATE")
            .param("id",id).query((rs,n)->obj("state",rs.getString(1),"input_document",rs.getString(2))).single();
    }
    private void batch(String sql,List<Object[]> args){
        for(int start=0;start<args.size();start+=100)batches.batchUpdate(sql,args.subList(start,Math.min(args.size(),start+100)));
    }
    private static Object decimal(Object x){
        if(x==null)return null;return new BigDecimal(x.toString()).setScale(6,RoundingMode.HALF_EVEN);
    }
    private void persist(UUID id,Map<String,Object> document,Map<String,Object> report){
        var canonical=new HashMap<String,Map<String,Object>>();for(Object x:list(map(document.get("canonical")).get("inputs"))){var r=map(x);canonical.put((String)r.get("instrumentId"),r);}
        var prepared=new HashMap<String,Map<String,Object>>();for(Object x:list(map(document.get("prepared")).get("rows"))){var r=map(x);prepared.put((String)r.get("instrument_id"),r);}
        var lineage=new ArrayList<Object[]>();var artifacts=new ArrayList<Object[]>();var scores=new ArrayList<Object[]>();var factors=new ArrayList<Object[]>();var exclusions=new ArrayList<Object[]>();
        for(Object x:list(report.get("rows"))){
            var row=map(x);String instrument=(String)row.get("instrument_id");UUID instrumentId=UUID.fromString(instrument);
            var source=canonical.get(instrument);var p=prepared.get(instrument);
            Instant maximum=null;for(Object e:map(p.get("provenance")).values()){
                var evidence=map(e);for(String key:List.of("available_at","observed_at"))if(evidence.get(key)!=null){Instant t=OffsetDateTime.parse(evidence.get(key).toString()).toInstant();if(maximum==null||maximum.isBefore(t))maximum=t;}
            }
            lineage.add(new Object[]{id,instrumentId,UUID.fromString((String)source.get("issuerId")),source.get("symbol"),CanonicalJson.write(source),CanonicalJson.write(p),maximum==null?null:maximum.atOffset(ZoneOffset.UTC)});
            var sources=new TreeSet<String>();
            for(String field:List.of("facts","priceLineage"))for(Object v:list(source.get(field))){Object a=map(v).get("sourceArtifactId");if(a!=null)sources.add(a.toString());}
            for(String a:sources)artifacts.add(new Object[]{id,instrumentId,UUID.fromString(a)});
            var family=map(row.get("families"));var contribution=map(row.get("contributions"));var metrics=map(row.get("metrics"));
            long available=metrics.values().stream().map(FrozenModel::map).filter(m->m.get("raw")!=null).count();
            scores.add(new Object[]{id,instrumentId,row.get("profile"),row.get("peer_group"),row.get("eligible"),
                decimal(family.get("value")),decimal(family.get("quality")),decimal(family.get("momentum")),
                decimal(contribution.get("value")),decimal(contribution.get("quality")),decimal(contribution.get("momentum")),
                decimal(row.get("composite")),decimal(row.get("percentile")),row.get("rank"),available,metrics.size()-available,
                CanonicalJson.write(row.get("warnings")),CanonicalJson.write(serialize(row))});
            for(var entry:metrics.entrySet()){
                var m=map(entry.getValue());String f=(String)m.get("family");double weight=f.equals("value")?.5/3:f.equals("quality")?.3/3:.2;
                factors.add(new Object[]{id,instrumentId,entry.getKey(),f,decimal(m.get("raw")),decimal(m.get("directed")),decimal(m.get("winsorized")),
                    m.get("cohort"),m.get("cohort_count"),decimal(m.get("lower")),decimal(m.get("upper")),decimal(m.get("mean")),decimal(m.get("sigma")),
                    decimal(m.get("z")),decimal(m.get("clipped_z")),decimal(weight),m.get("clipped_z")==null?(Boolean.TRUE.equals(row.get("eligible"))?BigDecimal.ZERO:null):decimal(((Number)m.get("clipped_z")).doubleValue()*weight),m.get("reason")});
                if(m.get("raw")==null)exclusions.add(new Object[]{id,instrumentId,"METRIC",m.getOrDefault("reason","NOT_REPORTED"),entry.getKey(),null});
            }
            for(Object reason:list(row.get("reasons")))exclusions.add(new Object[]{id,instrumentId,"ELIGIBILITY",reason,"",reason});
        }
        batch("INSERT INTO research.score_lineage VALUES(?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)",lineage);
        batch("INSERT INTO research.score_source_artifacts VALUES(?,?,?)",artifacts);
        batch("""
            INSERT INTO research.stock_scores(score_run_id,instrument_id,scoring_profile,peer_group,eligible,value_score,quality_score,momentum_score,
                value_contribution,quality_contribution,momentum_contribution,composite_z,composite_percentile,ordinal_rank,
                available_metric_count,missing_metric_count,warnings,output,published_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),clock_timestamp())
            """,scores);
        batch("INSERT INTO research.factor_observations VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",factors);
        batch("INSERT INTO research.score_exclusions VALUES(?,?,?,?,?,?)",exclusions);
    }
}
