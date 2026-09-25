package com.quantplatform.scoring.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.quantplatform.scoring.model.FrozenModel.*;
import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.scoring.inputs.*;
import com.quantplatform.scoring.model.*;
import com.quantplatform.scoring.runs.*;
import com.quantplatform.scoring.calculation.MonthEndScoringCoordinator;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class ScoringRunIntegrationTest extends DurableDeliveryFixture {
    static final LocalDate DAY=LocalDate.parse("2026-09-30");
    static final Instant CLOSE=Instant.parse("2026-09-30T20:00:00Z"),OPEN=Instant.parse("2026-10-01T13:30:00Z");
    ScoringInputRequest request;
    ScoringInputRepository repository;
    ScoringInputRepository.CrossSection section;
    Map<String,String> jurisdictions=new HashMap<>();
    UUID artifact;
    @BeforeEach void scoringFixture() throws Exception {
        UUID classification=jdbc.sql("INSERT INTO reference.classification_versions(source,version,mapping_checksum) VALUES('scoring','1',repeat('f',64)) RETURNING classification_version_id").query(UUID.class).single();
        artifact=jdbc.sql("""
            INSERT INTO operations.source_artifacts(dataset_id,request_key,source_uri,retrieved_at,content_hash,media_type,parser_version,inline_content)
            VALUES(:dataset,'scoring-fixture','test://golden','2026-08-01',repeat('e',64),'application/json','fixture','{}') RETURNING source_artifact_id
            """).param("dataset",dataset).query(UUID.class).single();
        var doc=CanonicalJson.readObject(Files.readString(Path.of("../research-engine/fixtures/model-v1/prepared.json")));
        var template=list(doc.get("rows")).stream().map(FrozenModel::map).filter(r->"GENERAL".equals(r.get("profile"))).findFirst().orElseThrow();
        var values=map(template.get("values"));var provenance=map(template.get("provenance"));
        var rows=new ArrayList<ScoringInput>();
        jdbc.sql("DELETE FROM reference.universe_memberships WHERE universe_snapshot_id IN (:sp,:nq)").param("sp",sp500).param("nq",nasdaq).update();
        for(int i=0;i<12;i++){
            UUID issuer=jdbc.sql("INSERT INTO reference.issuers(legal_name) VALUES(:name) RETURNING issuer_id").param("name","Golden "+i).query(UUID.class).single();
            UUID stock=jdbc.sql("""
                INSERT INTO reference.instruments(issuer_id,security_type,share_class,currency,primary_exchange_mic,valid_from)
                VALUES(:issuer,'COMMON_STOCK','A','USD','XNYS','2025-01-01') RETURNING instrument_id
                """).param("issuer",issuer).query(UUID.class).single();
            jurisdictions.put(issuer.toString(),"US");
            jdbc.sql("INSERT INTO reference.universe_memberships VALUES(:snapshot,:stock,:symbol,'XNYS',true,clock_timestamp())")
                .param("snapshot",sp500).param("stock",stock).param("symbol","G"+i).update();
            var facts=new ArrayList<ScoringInput.Fact>();
            for(var entry:values.entrySet()){
                String key=entry.getKey();String metric=key;LocalDate start=null;
                if(key.startsWith("ANNUAL_")||Set.of("RAW_CLOSE","ADJUSTED_CLOSE","MOMENTUM_OLD","MOMENTUM_RECENT","MEDIAN_DOLLAR_VOLUME","SHARES_OUTSTANDING").contains(key))continue;
                var evidence=map(provenance.get(key));LocalDate end=LocalDate.parse(evidence.get("period_end").toString());
                if(key.endsWith("_TTM")){metric=key.substring(0,key.length()-4);start=end.minusYears(1).plusDays(1);}
                else if(key.endsWith("_PRIOR"))metric=key.substring(0,key.length()-6);
                facts.add(new ScoringInput.Fact(UUID.randomUUID(),UUID.randomUUID(),metric,start,end,new BigDecimal(entry.getValue().toString()),"USD",
                    Instant.parse("2026-08-01T00:00:00Z"),Instant.parse("2026-08-01T00:00:00Z"),LocalDate.parse("2026-08-01"),artifact,"sec-us-gaap-v1","fixture"));
            }
            rows.add(new ScoringInput(stock,issuer,"G"+i,true,false,true,i==11?"UNSUPPORTED":"GENERAL","Technology","Technology",
                i==10?null:new BigDecimal(values.get("RAW_CLOSE").toString()),new BigDecimal(values.get("ADJUSTED_CLOSE").toString()),
                new BigDecimal(values.get("MOMENTUM_RECENT").toString()),new BigDecimal(values.get("MOMENTUM_OLD").toString()),252,20,
                new BigDecimal(values.get("MEDIAN_DOLLAR_VOLUME").toString()),new BigDecimal(values.get("SHARES_OUTSTANDING").toString()),UUID.randomUUID(),
                facts,List.of(new ScoringInput.PriceSource(DAY,"RAW","a".repeat(64),artifact)),List.of()));
        }
        jdbc.sql("UPDATE reference.universe_snapshots SET expected_member_count=12,imported_member_count=12 WHERE universe_snapshot_id=:id").param("id",sp500).update();
        jdbc.sql("UPDATE reference.universe_snapshots SET expected_member_count=0,imported_member_count=0 WHERE universe_snapshot_id=:id").param("id",nasdaq).update();
        for(String mode:List.of("RAW","SPLIT_DIVIDEND")){
            UUID definition=jdbc.sql("INSERT INTO operations.job_definitions(dataset_id,code,version,configuration,max_attempts) VALUES(:dataset,:code,'1',CAST(:config AS jsonb),3) RETURNING job_definition_id")
                .param("dataset",dataset).param("code","scoring-"+mode).param("config",CanonicalJson.write(obj("eventType","DAILY_PRICE","feed","sip","adjustment",mode,"adjustmentAsOf",DAY.toString()))).query(UUID.class).single();
            UUID run=jobs.plan(new com.quantplatform.marketdata.operations.IngestionPlan(definition,sp500,nasdaq,DAY,DAY,"backfill","scoring-fixture","test"));
            jdbc.sql("UPDATE operations.ingestion_runs SET status='COMPLETE',staged_count=expected_count,accepted_count=expected_count,completed_at=clock_timestamp() WHERE ingestion_run_id=:run").param("run",run).update();
            jdbc.sql("INSERT INTO operations.data_coverage(dataset_id,partition_key,boundary_date,ingestion_run_id,expected_count,accepted_count,validated_at) VALUES(:dataset,:mode,:date,:run,12,12,clock_timestamp())")
                .param("dataset",dataset).param("mode",mode).param("date",DAY).param("run",run).update();
        }

        request=new ScoringInputRequest(DAY,CLOSE,CLOSE,sp500,nasdaq,dataset,dataset,DAY,classification,"sec-us-gaap-v1",Set.of("TOTAL_ASSETS"));
        section=new ScoringInputRepository.CrossSection(request,rows);repository=mock(ScoringInputRepository.class);when(repository.load(request)).thenReturn(section);
    }
    ScoringRunService service(){return new ScoringRunService(source,tx,repository);}
    @Test void publishesExactUnionWithFactorsExclusionsAndSourceLineage(){
        UUID id=service().run(request,OPEN,jurisdictions);
        assertThat(text("SELECT state FROM research.scoring_runs")).isEqualTo("PUBLISHED");
        assertThat(count("research.stock_scores")).isEqualTo(12);
        assertThat(jdbc.sql("SELECT scored_count FROM research.scoring_runs").query(Integer.class).single()).isEqualTo(10);
        assertThat(jdbc.sql("SELECT excluded_count FROM research.scoring_runs").query(Integer.class).single()).isEqualTo(2);
        assertThat(count("research.factor_observations")).isEqualTo(77);
        assertThat(jdbc.sql("""
            SELECT count(*) FROM research.stock_scores s JOIN research.score_lineage l USING(score_run_id,instrument_id)
            JOIN research.score_source_artifacts a USING(score_run_id,instrument_id)
            JOIN operations.source_artifacts f USING(source_artifact_id)
            JOIN research.scoring_runs r USING(score_run_id) JOIN research.model_versions m USING(model_version_id)
            WHERE s.eligible AND f.source_uri='test://golden' AND m.manifest_sha256=:sha AND l.prepared_input->'provenance' IS NOT NULL
            """).param("sha",FrozenModel.SHA).query(Integer.class).single()).isEqualTo(10);
        assertThat(service().run(request,OPEN,jurisdictions)).isEqualTo(id);
        verify(repository,times(1)).load(request);
        assertThatThrownBy(()->jdbc.sql("DELETE FROM research.stock_scores WHERE score_run_id=:id").param("id",id).update()).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.sql("UPDATE research.scoring_runs SET expected_count=1 WHERE score_run_id=:id").param("id",id).update()).hasMessageContaining("immutable");
    }
    @Test void rollbackAfterBatchesResumesFrozenInputsWithoutReload(){
        var failing=new ScoringRunService(source,tx,repository){@Override protected void beforePublication(UUID id){throw new IllegalStateException("injected crash");}};
        assertThatThrownBy(()->failing.run(request,OPEN,jurisdictions)).hasMessageContaining("injected crash");
        assertThat(count("research.stock_scores")).isZero();assertThat(count("research.score_lineage")).isZero();
        assertThat(text("SELECT state FROM research.scoring_runs")).isEqualTo("FAILED");
        String hash=text("SELECT input_sha256 FROM research.scoring_runs");
        when(repository.load(request)).thenThrow(new IllegalStateException("inputs changed after capture"));
        service().run(request,OPEN,jurisdictions);
        assertThat(text("SELECT input_sha256 FROM research.scoring_runs")).isEqualTo(hash);
        assertThat(jdbc.sql("SELECT attempts FROM research.scoring_runs").query(Integer.class).single()).isEqualTo(2);
        verify(repository,times(1)).load(request);
    }
    @Test void simultaneousWorkersProduceOneLogicalRun() throws Exception {
        var service=service();try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->service.run(request,OPEN,jurisdictions));
            var second=executor.submit(()->service.run(request,OPEN,jurisdictions));
            assertThat(first.get(60,TimeUnit.SECONDS)).isEqualTo(second.get(60,TimeUnit.SECONDS));
        }
        assertThat(count("research.scoring_runs")).isEqualTo(1);assertThat(count("research.stock_scores")).isEqualTo(12);
        verify(repository,times(1)).load(request);
    }
    @Test void databaseRejectsMissingFactorDespiteMatchingCounts(){
        var broken=new ScoringRunService(source,tx,repository){@Override protected void beforePublication(UUID id){
            jdbc.sql("DELETE FROM research.factor_observations WHERE score_run_id=:id AND metric='momentum'").param("id",id).update();
        }};
        assertThatThrownBy(()->broken.run(request,OPEN,jurisdictions)).hasMessageContaining("Missing factors");
        assertThat(text("SELECT state FROM research.scoring_runs")).isEqualTo("FAILED");
        assertThat(count("research.stock_scores")).isZero();
    }
    @Test void databaseRejectsMissingExpectedInstrument(){
        when(repository.load(request)).thenReturn(new ScoringInputRepository.CrossSection(request,section.inputs().subList(0,11)));
        assertThatThrownBy(()->service().run(request,OPEN,jurisdictions)).hasMessageContaining("exact snapshot union");
        assertThat(text("SELECT state FROM research.scoring_runs")).isEqualTo("FAILED");
    }
    @Test void failedQueryRetriesAndUncertifiedModelCannotBeApproved(){
        assertThatThrownBy(()->jdbc.sql("UPDATE research.model_versions SET approval_state='APPROVED'").update()).hasMessageContaining("without matching parity");
        when(repository.load(request)).thenThrow(new IllegalArgumentException("incomplete snapshots")).thenReturn(section);
        var service=service();assertThatThrownBy(()->service.run(request,OPEN,jurisdictions)).hasMessageContaining("incomplete snapshots");
        assertThat(text("SELECT state FROM research.scoring_runs")).isEqualTo("FAILED");
        service.run(request,OPEN,jurisdictions);verify(repository,times(2)).load(request);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void realDelayedCanonicalInputsPublishAndRetryWithoutFutureLeakage(boolean tooLate) {
        jdbc.sql("""
            INSERT INTO reference.trading_sessions(exchange_mic,session_date,opens_at,closes_at,timezone,holiday,early_close,source,available_at,observed_at)
            SELECT 'XNYS',d::date,CASE WHEN extract(isodow FROM d)<6 THEN (d+interval '13 hours 30 minutes') AT TIME ZONE 'UTC' END,
                CASE WHEN extract(isodow FROM d)<6 THEN (d+interval '20 hours') AT TIME ZONE 'UTC' END,
                'America/New_York',extract(isodow FROM d)>5,false,'fixture','2025-01-01','2025-01-01'
            FROM generate_series('2025-06-01'::timestamp,'2026-10-01',interval '1 day') d
            """).update();
        for(var row:section.inputs()) {
            jdbc.sql("INSERT INTO reference.instrument_symbols(instrument_id,symbol,exchange_mic,effective_from,source,available_at,observed_at) VALUES(:id,:symbol,'XNYS','2025-01-01','fixture','2025-01-01','2025-01-01')")
                .param("id",row.instrumentId()).param("symbol",row.symbol()).update();
            jdbc.sql("INSERT INTO reference.issuer_classifications(issuer_id,classification_version_id,mapped_sector,effective_from,available_at,observed_at) VALUES(:issuer,:version,'Technology','2025-01-01','2025-01-01','2025-01-01')")
                .param("issuer",row.issuerId()).param("version",request.classificationVersion()).update();
            String observation=CanonicalJson.sha256(row.instrumentId().toString());
            jdbc.sql("INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,economic_time,adjustment_mode,payload,source_artifact_id,observed_at) VALUES(:key,:dataset,:stock,'FILING_FACTS','2026-06-30','NONE','{}',:artifact,'2026-08-01')")
                .param("key",observation).param("dataset",dataset).param("stock",row.instrumentId()).param("artifact",artifact).update();
            UUID filing=jdbc.sql("""
                INSERT INTO fundamentals.filings(issuer_id,accession,revision_hash,form,fiscal_period_end,filed_date,accepted_at,published_at,available_at,observed_at,ingested_at,primary_document,parser_version,mapping_version,source_artifact_id,observation_key)
                VALUES(:issuer,:accession,repeat('a',64),'10-Q','2026-06-30','2026-08-01','2026-08-01','2026-08-01','2026-08-01','2026-08-01','2026-08-01','fixture','fixture','sec-us-gaap-v1',:artifact,:key) RETURNING filing_id
                """).param("issuer",row.issuerId()).param("accession",row.instrumentId().toString()).param("artifact",artifact).param("key",observation).query(UUID.class).single();
            var facts=new ArrayList<>(row.facts());
            facts.add(new ScoringInput.Fact(row.shareFactId(),filing,"SHARES_OUTSTANDING",null,LocalDate.parse("2026-06-30"),row.sharesOutstanding(),"shares",Instant.parse("2026-08-01T00:00:00Z"),Instant.parse("2026-08-01T00:00:00Z"),LocalDate.parse("2026-08-01"),artifact,"sec-us-gaap-v1","fixture"));
            for(var fact:facts) {
                jdbc.sql("""
                    INSERT INTO fundamentals.fundamental_facts(fact_id,issuer_id,filing_id,metric_code,mapping_version,taxonomy,source_concept,period_start,period_end,source_value,numeric_value,unit,dimensions,source_context,source_fact_hash,priority,quality_state,available_at,observed_at,source_artifact_id)
                    VALUES(:id,:issuer,:filing,:metric,'sec-us-gaap-v1','us-gaap',:metric,:start,:end,:value,:value,:unit,'{}','{}',:hash,1,'VALID','2026-08-01','2026-08-01',:artifact)
                    """).param("id",fact.factId()).param("issuer",row.issuerId()).param("filing",filing).param("metric",fact.metric()).param("start",fact.start()).param("end",fact.end()).param("value",fact.value()).param("unit",fact.unit()).param("hash",CanonicalJson.sha256(fact.factId().toString())).param("artifact",artifact).update();
            }
            jdbc.sql("INSERT INTO fundamentals.instrument_share_facts VALUES(:stock,:fact,'SINGLE_SHARE_CLASS','2026-08-01')").param("stock",row.instrumentId()).param("fact",row.shareFactId()).update();
            jdbc.sql("INSERT INTO fundamentals.profile_observations VALUES(:issuer,:key,'GENERAL','FACTS_AVAILABLE','fixture','2026-08-01',:artifact)").param("issuer",row.issuerId()).param("key",observation).param("artifact",artifact).update();
        }
        jdbc.sql("""
            INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,economic_time,adjustment_mode,payload,source_artifact_id,observed_at,ingested_at)
            SELECT md5(i.instrument_id::text||s.session_date||m.mode)||md5(i.instrument_id::text||s.session_date||m.mode),:dataset,i.instrument_id,'DAILY_PRICE',s.session_date,m.mode,'{}',:artifact,'2026-10-01T12:00:00Z','2026-10-01T12:01:00Z'
            FROM reference.instruments i CROSS JOIN reference.trading_sessions s CROSS JOIN (VALUES('RAW'),('SPLIT_DIVIDEND')) m(mode)
            WHERE s.exchange_mic='XNYS' AND NOT s.holiday AND s.session_date<='2026-09-30'
            """).param("dataset",dataset).param("artifact",artifact).update();
        jdbc.sql("""
            INSERT INTO market_data.daily_bar_observations(session_date,observation_key,instrument_id,provider_id,dataset_id,exchange_mic,feed,adjustment_mode,adjustment_as_of,currency,bar_time,open,high,low,close,volume,trade_count,source_revision,source_artifact_id,available_at,observed_at,ingested_at)
            SELECT o.economic_time::date,o.observation_key,o.instrument_id,d.provider_id,o.dataset_id,'XNYS','sip',o.adjustment_mode,CASE WHEN o.adjustment_mode='SPLIT_DIVIDEND' THEN '2026-10-01'::date END,'USD',o.economic_time,100,110,90,100,1000000,1000,repeat('d',64),:artifact,'2026-10-01T12:00:00Z','2026-10-01T12:00:00Z','2026-10-01T12:01:00Z'
            FROM market_data.observations o JOIN operations.datasets d USING(dataset_id) WHERE o.event_type='DAILY_PRICE'
            """).param("artifact",artifact).update();
        jdbc.sql("UPDATE operations.job_definitions SET configuration=jsonb_set(configuration,'{adjustmentAsOf}','\"2026-10-01\"') WHERE code='scoring-SPLIT_DIVIDEND'").update();
        repository=new ScoringInputRepository(source);
        var coordinator=new MonthEndScoringCoordinator(source,service(),clock("2026-10-01T13:00:00Z"),dataset.toString(),dataset.toString(),request.classificationVersion().toString());
        jdbc.sql("UPDATE operations.data_coverage SET valid=false").update();
        assertThat(coordinator.calculate(DAY)).isFalse();
        assertThat(count("research.scoring_runs")).isZero();
        jdbc.sql("UPDATE operations.data_coverage SET valid=true").update();
        if(tooLate) {
            jdbc.sql("UPDATE market_data.daily_bar_observations SET observed_at='2026-10-01T13:01:00Z'").update();
            assertThatThrownBy(()->coordinator.calculate(DAY)).hasMessageContaining("INSUFFICIENT_UNIVERSE");
            assertThat(text("SELECT state FROM research.scoring_runs")).isEqualTo("FAILED");
            assertThat(count("research.stock_scores")).isZero();
            return;
        }
        assertThat(coordinator.calculate(DAY)).isTrue();
        assertThat(jdbc.sql("SELECT scored_count FROM research.scoring_runs").query(Integer.class).single()).isEqualTo(12);
        assertThat(text("SELECT state FROM research.scoring_runs")).isEqualTo("PUBLISHED");
        assertThat(text("SELECT request->'inputs'->>'timingPolicy' FROM research.scoring_runs")).isEqualTo(ScoringInputRequest.PRE_OPEN);
        assertThat(jdbc.sql("SELECT knowledge_cutoff<effective_from FROM research.scoring_runs").query(Boolean.class).single()).isTrue();
        String captured=text("SELECT input_sha256 FROM research.scoring_runs");
        // A later provider correction cannot change a captured run on restart.
        jdbc.sql("UPDATE market_data.daily_bar_observations SET close=105,observed_at='2026-10-01T13:01:00Z'").update();
        assertThat(coordinator.calculate(DAY)).isTrue();
        assertThat(count("research.scoring_runs")).isEqualTo(1);
        assertThat(text("SELECT input_sha256 FROM research.scoring_runs")).isEqualTo(captured);
    }

    @Test void delayedDailyRunWaitsUntilTheFixedPreOpenDecision() {
        calendar(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-01"));
        jdbc.sql("UPDATE reference.trading_sessions SET available_at='2026-01-01',observed_at='2026-01-01'").update();
        var coordinator=new MonthEndScoringCoordinator(source,service(),
            Clock.fixed(Instant.parse("2026-09-30T21:00:00Z"),ZoneOffset.UTC),
            dataset.toString(),dataset.toString(),request.classificationVersion().toString());
        assertThat(coordinator.calculate(DAY)).isFalse();
        assertThat(count("research.scoring_runs")).isZero();
    }
    @Test void lateNextOpeningCannotBeSkippedForALaterKnownSession(){
        calendar(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-10-02"));
        jdbc.sql("UPDATE reference.trading_sessions SET available_at='2026-01-01',observed_at='2026-01-01'").update();
        jdbc.sql("UPDATE reference.trading_sessions SET observed_at='2026-10-01T12:00:00Z' WHERE session_date='2026-10-01'").update();
        var coordinator=new MonthEndScoringCoordinator(source,service(),clock("2026-10-02T12:00:00Z"),
            dataset.toString(),dataset.toString(),request.classificationVersion().toString());
        assertThat(coordinator.calculate(DAY)).isFalse();
        assertThat(count("research.scoring_runs")).isZero();
        verifyNoInteractions(repository);
    }
    @Test void databaseRejectsIncorrectPreOpenExecutionAndLateCalendar(){
        calendar(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-10-01"));
        jdbc.sql("UPDATE reference.trading_sessions SET available_at='2026-01-01',observed_at='2026-01-01'").update();
        var delayed=new ScoringInputRequest(DAY,CLOSE,OPEN.minusSeconds(1800),sp500,nasdaq,dataset,dataset,
            DAY.plusDays(1),request.classificationVersion(),"sec-us-gaap-v1",Set.of("TOTAL_ASSETS"),ScoringInputRequest.PRE_OPEN);
        assertThatThrownBy(()->service().run(delayed,OPEN.plusSeconds(60),jurisdictions))
            .hasMessageContaining("Invalid pre-open scoring timing or calendar");
        jdbc.sql("UPDATE reference.trading_sessions SET observed_at='2026-10-01T12:00:00Z' WHERE session_date='2026-10-01'").update();
        assertThatThrownBy(()->service().run(delayed,OPEN,jurisdictions))
            .hasMessageContaining("Invalid pre-open scoring timing or calendar");
        assertThat(count("research.scoring_runs")).isZero();
        verifyNoInteractions(repository);
    }
    @Test void newStartupClockDoesNotChangeMonthEndIdentity(){
        calendar(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-10-01"));
        // Calendar source was known before this signal.
        jdbc.sql("UPDATE reference.trading_sessions SET available_at='2026-01-01',observed_at='2026-01-01'").update();
        var first=new MonthEndScoringCoordinator(source,service(),Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"),ZoneOffset.UTC),dataset.toString(),dataset.toString(),request.classificationVersion().toString());
        jdbc.sql("UPDATE operations.job_definitions SET configuration=jsonb_set(configuration,'{adjustmentAsOf}','\"2026-10-01\"') WHERE code='scoring-SPLIT_DIVIDEND'").update();
        var original=section;
        request=new ScoringInputRequest(DAY,CLOSE,OPEN.minusSeconds(1800),sp500,nasdaq,dataset,dataset,DAY.plusDays(1),request.classificationVersion(),"sec-us-gaap-v1",Set.of("TOTAL_ASSETS"),ScoringInputRequest.PRE_OPEN);
        section=new ScoringInputRepository.CrossSection(request,original.inputs());
        when(repository.load(request)).thenReturn(section);
        // The coordinator deliberately has no inferred tax jurisdiction; other quality metrics still cover the family.
        assertThat(first.calculate(DAY)).isTrue();
        var restarted=new MonthEndScoringCoordinator(source,service(),Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneOffset.UTC),dataset.toString(),dataset.toString(),request.classificationVersion().toString());
        assertThat(restarted.calculate(DAY)).isTrue();
        assertThat(count("research.scoring_runs")).isEqualTo(1);verify(repository,times(1)).load(request);
    }
    @Test void insertionCannotBypassPublicationAndCapturedInputsCannotChange(){
        var failing=new ScoringRunService(source,tx,repository){@Override protected void beforePublication(UUID id){throw new IllegalStateException("stop");}};
        assertThatThrownBy(()->failing.run(request,OPEN,jurisdictions)).hasMessageContaining("stop");
        assertThatThrownBy(()->jdbc.sql("UPDATE research.scoring_runs SET input_document='{}',input_sha256=encode(sha256(convert_to('{}','UTF8')),'hex')").update()).hasMessageContaining("Captured");
        assertThatThrownBy(()->jdbc.sql("""
            INSERT INTO research.scoring_runs(logical_key,as_of_date,market_cutoff,knowledge_cutoff,effective_from,
                sp500_snapshot_id,nasdaq100_snapshot_id,model_version_id,certification_id,candidate,input_version,request,state)
            SELECT repeat('a',64),as_of_date,market_cutoff,knowledge_cutoff,effective_from,sp500_snapshot_id,nasdaq100_snapshot_id,
                model_version_id,certification_id,candidate,input_version,request,'PUBLISHED' FROM research.scoring_runs
            """).update()).hasMessageContaining("must start pending");
    }
    @Test void publicationRejectsChangedLineageAndMissingExclusions(){
        var broken=new ScoringRunService(source,tx,repository){@Override protected void beforePublication(UUID id){
            jdbc.sql("UPDATE research.score_lineage SET prepared_input='{}' WHERE score_run_id=:id").param("id",id).update();
        }};
        assertThatThrownBy(()->broken.run(request,OPEN,jurisdictions)).hasMessageContaining("lineage differs");
        var noExclusions=new ScoringRunService(source,tx,repository){@Override protected void beforePublication(UUID id){
            jdbc.sql("DELETE FROM research.score_exclusions WHERE score_run_id=:id AND stage='ELIGIBILITY'").param("id",id).update();
        }};
        assertThatThrownBy(()->noExclusions.run(request,OPEN,jurisdictions)).hasMessageContaining("Missing factors");
        assertThat(count("research.stock_scores")).isZero();
    }
    @Test void explicitNewInputConfigurationSupersedesWithoutMutatingPublishedRun(){
        UUID first=service().run(request,OPEN,jurisdictions);
        UUID next=service().run(request,OPEN,Map.of());
        assertThat(next).isNotEqualTo(first);
        assertThat(jdbc.sql("SELECT supersedes FROM research.scoring_runs WHERE score_run_id=:id").param("id",next).query(UUID.class).single()).isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM research.scoring_runs WHERE state='PUBLISHED'").query(Integer.class).single()).isEqualTo(2);
    }

    @Test void incompleteDailyCollectionDoesNotFreezePartialInputs(){
        jdbc.sql("UPDATE operations.data_coverage SET valid=false WHERE partition_key='SPLIT_DIVIDEND'").update();
        var service=service();
        assertThat(service.inputsReady(request)).isFalse();
        assertThatThrownBy(()->service.run(request,OPEN,jurisdictions)).hasMessageContaining("WAITING_FOR_DAILY_COVERAGE");
        verifyNoInteractions(repository);
        assertThat(jdbc.sql("SELECT input_document IS NULL FROM research.scoring_runs").query(Boolean.class).single()).isTrue();
        jdbc.sql("UPDATE operations.data_coverage SET valid=true").update();
        service.run(request,OPEN,jurisdictions);
        assertThat(text("SELECT state FROM research.scoring_runs")).isEqualTo("PUBLISHED");
    }
}
