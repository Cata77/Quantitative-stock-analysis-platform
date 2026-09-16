package com.quantplatform.scoring.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.quantplatform.scoring.inputs.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

class ScoringInputIntegrationTest extends DurableDeliveryFixture {
    static final LocalDate DAY=LocalDate.parse("2026-09-01");
    static final Instant CLOSE=Instant.parse("2026-09-01T20:00:00Z");
    UUID classification,artifact;
    ScoringInputRepository repository;

    @BeforeEach void inputs() {
        source.setUrl(source.getUrl()+"?socketTimeout=120&connectTimeout=10");
        jdbc.sql("UPDATE reference.instruments SET valid_from='2025-01-01'").update();
        jdbc.sql("""
            INSERT INTO reference.trading_sessions(exchange_mic,session_date,opens_at,closes_at,timezone,
                holiday,early_close,source,available_at,observed_at)
            SELECT e,d::date,CASE WHEN extract(isodow FROM d)<6 THEN (d+interval '13 hours 30 minutes') AT TIME ZONE 'UTC' END,
                CASE WHEN extract(isodow FROM d)<6 THEN (d+interval '20 hours') AT TIME ZONE 'UTC' END,
                'America/New_York',extract(isodow FROM d)>5,false,'fixture','2025-01-01','2025-01-01'
            FROM generate_series('2025-06-01'::timestamp,'2026-09-01',interval '1 day') d
            CROSS JOIN (VALUES('XNYS'),('XNAS')) exchanges(e)
            """).update();
        classification=jdbc.sql("""
            INSERT INTO reference.classification_versions(source,version,mapping_checksum)
            VALUES('fixture','1',repeat('a',64)) RETURNING classification_version_id
            """).query(UUID.class).single();
        artifact=jdbc.sql("""
            INSERT INTO operations.source_artifacts(dataset_id,request_key,source_uri,retrieved_at,
                content_hash,media_type,parser_version,inline_content)
            VALUES(:dataset,'input-fixture','test://input','2026-08-01',repeat('b',64),
                'application/json','fixture','{}') RETURNING source_artifact_id
            """).param("dataset",dataset).query(UUID.class).single();
        seed();
        repository=new ScoringInputRepository(source);
    }

    void seed() {
        jdbc.sql("""
            INSERT INTO reference.issuer_classifications(issuer_id,classification_version_id,mapped_sector,
                effective_from,available_at,observed_at)
            SELECT issuer_id,:classification,'Technology','2025-01-01','2025-01-01','2025-01-01'
            FROM reference.issuers ON CONFLICT DO NOTHING
            """).param("classification",classification).update();
        jdbc.sql("""
            INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,
                economic_time,adjustment_mode,payload,source_artifact_id,observed_at,ingested_at)
            SELECT md5(instrument_id::text)||md5(instrument_id::text),:dataset,instrument_id,'FILING_FACTS',
                '2026-06-30','NONE','{}',:artifact,'2026-08-01','2026-08-01'
            FROM reference.instruments ON CONFLICT DO NOTHING
            """).param("dataset",dataset).param("artifact",artifact).update();
        jdbc.sql("""
            INSERT INTO fundamentals.filings(issuer_id,accession,revision_hash,form,fiscal_period_end,filed_date,
                accepted_at,published_at,available_at,observed_at,ingested_at,primary_document,parser_version,
                mapping_version,source_artifact_id,observation_key)
            SELECT issuer_id,'fixture-'||instrument_id,repeat('c',64),'10-Q','2026-06-30','2026-08-01',
                '2026-08-01','2026-08-01','2026-08-01','2026-08-01','2026-08-01','fixture.htm','fixture',
                'sec-us-gaap-v1',:artifact,md5(instrument_id::text)||md5(instrument_id::text)
            FROM reference.instruments ON CONFLICT DO NOTHING
            """).param("artifact",artifact).update();
        jdbc.sql("""
            INSERT INTO fundamentals.fundamental_facts(issuer_id,filing_id,metric_code,mapping_version,taxonomy,
                source_concept,period_end,source_value,numeric_value,unit,dimensions,source_context,source_fact_hash,
                priority,quality_state,available_at,observed_at,source_artifact_id)
            SELECT l.issuer_id,l.filing_id,m.code,'sec-us-gaap-v1','us-gaap',m.concept,'2026-06-30',1000,1000,
                m.unit,'{}','{}',md5(m.code)||md5(m.code),1,'VALID','2026-08-01','2026-08-01',:artifact
            FROM fundamentals.filings l CROSS JOIN (VALUES ('TOTAL_ASSETS','Assets','USD'),
                ('SHARES_OUTSTANDING','CommonStockSharesOutstanding','shares')) m(code,concept,unit)
            ON CONFLICT DO NOTHING
            """).param("artifact",artifact).update();
        jdbc.sql("""
            INSERT INTO fundamentals.instrument_share_facts
            SELECT i.instrument_id,f.fact_id,'SINGLE_SHARE_CLASS','2026-08-01'
            FROM fundamentals.fundamental_facts f JOIN reference.instruments i USING(issuer_id)
            WHERE f.metric_code='SHARES_OUTSTANDING' ON CONFLICT DO NOTHING
            """).update();
        jdbc.sql("""
            INSERT INTO fundamentals.profile_observations
            SELECT issuer_id,md5(instrument_id::text)||md5(instrument_id::text),'GENERAL','FACTS_AVAILABLE',
                'fixture','2026-08-01',:artifact FROM reference.instruments ON CONFLICT DO NOTHING
            """).param("artifact",artifact).update();
        jdbc.sql("""
            INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,
                economic_time,adjustment_mode,payload,source_artifact_id,observed_at,ingested_at)
            SELECT md5(i.instrument_id::text||s.session_date||m.mode)||md5(i.instrument_id::text||s.session_date||m.mode),
                :dataset,i.instrument_id,'DAILY_PRICE',s.session_date,m.mode,'{}',:artifact,'2026-08-31','2026-08-31'
            FROM reference.instruments i CROSS JOIN reference.trading_sessions s
            CROSS JOIN (VALUES('RAW'),('SPLIT_DIVIDEND')) m(mode)
            WHERE s.exchange_mic='XNAS' AND NOT s.holiday ON CONFLICT DO NOTHING
            """).param("dataset",dataset).param("artifact",artifact).update();
        jdbc.sql("""
            INSERT INTO market_data.daily_bar_observations(session_date,observation_key,instrument_id,provider_id,
                dataset_id,exchange_mic,feed,adjustment_mode,adjustment_as_of,currency,bar_time,open,high,low,
                close,volume,trade_count,source_revision,source_artifact_id,available_at,observed_at,ingested_at)
            SELECT o.economic_time::date,o.observation_key,o.instrument_id,d.provider_id,o.dataset_id,'XNAS','sip',
                o.adjustment_mode,CASE WHEN o.adjustment_mode='SPLIT_DIVIDEND' THEN '2026-09-01'::date END,
                'USD',o.economic_time,100,200,90,100+extract(doy FROM o.economic_time)/10,100000,1000,
                repeat('d',64),:artifact,'2026-09-01T19:00:00Z','2026-09-01T19:00:00Z','2026-09-01T19:00:00Z'
            FROM market_data.observations o JOIN operations.datasets d USING(dataset_id)
            WHERE o.event_type='DAILY_PRICE' ON CONFLICT DO NOTHING
            """).param("artifact",artifact).update();
    }

    ScoringInputRequest request() {
        return new ScoringInputRequest(DAY,CLOSE,CLOSE,sp500,nasdaq,dataset,dataset,DAY,
            classification,"sec-us-gaap-v1",Set.of("TOTAL_ASSETS"));
    }

    @Test void returnsOneImmutableCompleteRowWithCalendarBoundariesAndLineage() {
        var result=repository.load(request());
        assertThat(result.inputs()).hasSize(1);
        assertThat(result.readyCount()).isEqualTo(1);
        assertThat(result.excludedCount()).isZero();
        var row=result.inputs().getFirst();
        assertThat(row.historyCount()).isEqualTo(252);
        assertThat(row.liquidityCount()).isEqualTo(20);
        assertThat(row.inSp500()&&row.inNasdaq100()).isTrue();
        assertThat(row.facts()).hasSize(2);
        assertThat(row.facts()).allSatisfy(fact -> {
            var stored=jdbc.sql("SELECT observed_at FROM fundamentals.fundamental_facts WHERE fact_id=:id")
                .param("id",fact.factId()).query((rs,n)->rs.getTimestamp(1).toInstant()).single();
            assertThat(fact.observedAt()).isEqualTo(stored);
        });
        assertThat(row.priceLineage()).hasSize(23);
        assertThat(row.sharesOutstanding()).isEqualByComparingTo("1000");
        var boundary=jdbc.sql("""
            SELECT 100+extract(doy FROM session_date)/10 FROM reference.trading_sessions
            WHERE exchange_mic='XNYS' AND NOT holiday AND session_date<='2026-09-01'
            ORDER BY session_date DESC OFFSET 21 LIMIT 1
            """).query(java.math.BigDecimal.class).single();
        assertThat(row.momentumRecent()).isEqualByComparingTo(boundary);
        assertThatThrownBy(()->result.inputs().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void missingMemberIsRetainedAndAccountingBalances() {
        jdbc.sql("""
            INSERT INTO reference.instruments(issuer_id,security_type,share_class,currency,primary_exchange_mic,valid_from)
            SELECT issuer_id,'COMMON_STOCK','B','USD','XNAS','2025-01-01' FROM reference.instruments
            """).update();
        jdbc.sql("""
            INSERT INTO reference.universe_memberships
                (universe_snapshot_id,instrument_id,source_symbol,source_exchange_mic,primary_liquid_class)
            SELECT :snapshot,instrument_id,'OTHER','XNAS',false FROM reference.instruments WHERE share_class='B'
            """).param("snapshot",sp500).update();
        jdbc.sql("UPDATE reference.universe_snapshots SET expected_member_count=2,imported_member_count=2 WHERE universe_snapshot_id=:id")
            .param("id",sp500).update();
        var result=repository.load(request());
        assertThat(result.inputs()).hasSize(2);
        assertThat(result.readyCount()+result.excludedCount()).isEqualTo(2);
        var missing=result.inputs().stream().filter(r->!r.instrumentId().equals(instrument)).findFirst().orElseThrow();
        assertThat(missing.reasons()).extracting(ScoringInput.Reason::code)
            .contains("NON_PRIMARY_SHARE_CLASS","MISSING_SCORE_DATE_PRICE","MISSING_INSTRUMENT_SHARES");
    }

    @Test void futureCorrectionsAndResolvedQualityIssuesRespectKnowledgeCutoff() {
        jdbc.sql("""
            INSERT INTO operations.data_quality_issues(dataset_id,instrument_id,severity,issue_type,
                affected_key,evidence,status,detected_at,resolved_at)
            VALUES(:dataset,:instrument,'BLOCKING','CORPORATE_ACTION_REVIEW','fixture','{}','RESOLVED',
                '2026-09-01T19:30:00Z','2026-09-02')
            """).param("dataset",dataset).param("instrument",instrument).update();
        jdbc.sql("""
            UPDATE fundamentals.fundamental_facts SET numeric_value=9000,available_at='2026-09-02',
                observed_at='2026-09-02' WHERE metric_code='TOTAL_ASSETS'
            """).update();
        var row=repository.load(request()).inputs().getFirst();
        assertThat(row.reasons()).extracting(ScoringInput.Reason::code)
            .contains("CORPORATE_ACTION_REVIEW","MISSING_REQUIRED_METRIC");
        assertThat(row.facts()).extracting(ScoringInput.Fact::metric).doesNotContain("TOTAL_ASSETS");
    }

    @Test void gapsCannotShiftMomentumOrUseIexVolume() {
        jdbc.sql("""
            UPDATE market_data.daily_bar_observations SET feed='iex'
            WHERE session_date=(SELECT session_date FROM reference.trading_sessions
                WHERE exchange_mic='XNYS' AND NOT holiday AND session_date<='2026-09-01'
                ORDER BY session_date DESC OFFSET 21 LIMIT 1)
            """).update();
        jdbc.sql("UPDATE market_data.daily_bar_observations SET feed='iex' WHERE adjustment_mode='RAW'").update();
        var row=repository.load(request()).inputs().getFirst();
        assertThat(row.momentumRecent()).isNull();
        assertThat(row.reasons()).extracting(ScoringInput.Reason::code)
            .contains("MISSING_MOMENTUM_BOUNDARY","INSUFFICIENT_PRICE_HISTORY","INCOMPLETE_LIQUIDITY_WINDOW");
    }

    @Test void invalidSnapshotPairFailsTheWholeRequest() {
        jdbc.sql("UPDATE reference.universe_snapshots SET observed_at='2026-09-02' WHERE universe_snapshot_id=:id")
            .param("id",nasdaq).update();
        assertThatThrownBy(()->repository.load(request())).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SNAPSHOT_NOT_POINT_IN_TIME_COMPLETE");
    }

    @Test void appendedFilingRevisionAndClassificationAreSelectedOnlyWhenKnown() {
        jdbc.sql("""
            INSERT INTO fundamentals.filings(issuer_id,accession,revision_hash,form,fiscal_period_end,filed_date,
                accepted_at,published_at,available_at,observed_at,ingested_at,primary_document,parser_version,
                mapping_version,source_artifact_id,observation_key)
            SELECT issuer_id,accession,repeat('e',64),form,fiscal_period_end,filed_date,accepted_at,published_at,
                '2026-09-01T19:30:00Z','2026-09-01T19:30:00Z','2026-09-01T19:30:00Z',primary_document,
                parser_version,mapping_version,source_artifact_id,observation_key FROM fundamentals.filings
            """).update();
        jdbc.sql("""
            INSERT INTO fundamentals.fundamental_facts(issuer_id,filing_id,metric_code,mapping_version,taxonomy,
                source_concept,period_end,source_value,numeric_value,unit,dimensions,source_context,source_fact_hash,
                priority,quality_state,available_at,observed_at,source_artifact_id)
            SELECT issuer_id,filing_id,'TOTAL_ASSETS',mapping_version,'us-gaap','Assets',fiscal_period_end,9000,9000,
                'USD','{}','{}',repeat('e',64),1,'VALID','2026-09-01T19:30:00Z','2026-09-01T19:30:00Z',source_artifact_id
            FROM fundamentals.filings WHERE revision_hash=repeat('e',64)
            """).update();
        var before=new ScoringInputRequest(DAY,CLOSE,Instant.parse("2026-09-01T19:15:00Z"),
            sp500,nasdaq,dataset,dataset,DAY,classification,"sec-us-gaap-v1",Set.of("TOTAL_ASSETS"));
        var old=repository.load(before).inputs().getFirst();
        assertThat(old.facts().stream().filter(f->f.metric().equals("TOTAL_ASSETS")).findFirst().orElseThrow().value())
            .isEqualByComparingTo("1000");
        var current=repository.load(request()).inputs().getFirst();
        assertThat(current.facts()).hasSize(1);
        assertThat(current.facts().getFirst().value()).isEqualByComparingTo("9000");
        assertThat(current.sharesOutstanding()).isNull();
        jdbc.sql("UPDATE reference.issuer_classifications SET available_at='2026-09-02',observed_at='2026-09-02'").update();
        assertThat(repository.load(request()).inputs().getFirst().sector()).isNull();
    }

    @Test void rejectedLatestPriceRevisionCannotResurrectAnOlderValidBar() {
        jdbc.sql("""
            INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,
                economic_time,adjustment_mode,payload,source_artifact_id,observed_at,ingested_at)
            VALUES(repeat('e',64),:dataset,:instrument,'DAILY_PRICE','2026-09-01','RAW','{}',:artifact,
                '2026-09-01T19:30:00Z','2026-09-01T19:30:00Z')
            """).param("dataset",dataset).param("instrument",instrument).param("artifact",artifact).update();
        jdbc.sql("""
            INSERT INTO market_data.daily_bar_observations(session_date,observation_key,instrument_id,provider_id,
                dataset_id,exchange_mic,feed,adjustment_mode,adjustment_as_of,currency,bar_time,open,high,low,
                close,volume,trade_count,source_revision,source_artifact_id,available_at,observed_at,ingested_at,quality_state)
            SELECT session_date,repeat('e',64),instrument_id,provider_id,dataset_id,exchange_mic,feed,adjustment_mode,
                adjustment_as_of,currency,bar_time,open,high,low,close,volume,trade_count,repeat('e',64),source_artifact_id,
                '2026-09-01T19:30:00Z','2026-09-01T19:30:00Z','2026-09-01T19:30:00Z','REJECTED'
            FROM market_data.daily_bar_observations WHERE adjustment_mode='RAW' AND session_date='2026-09-01'
            """).update();
        var before=new ScoringInputRequest(DAY,CLOSE,Instant.parse("2026-09-01T19:15:00Z"),
            sp500,nasdaq,dataset,dataset,DAY,classification,"sec-us-gaap-v1",Set.of("TOTAL_ASSETS"));
        assertThat(repository.load(before).inputs().getFirst().rawClose()).isNotNull();
        assertThat(repository.load(request()).inputs().getFirst().rawClose()).isNull();
    }

    @Test void aNewerKnownSnapshotSupersedesTheOldMembership() {
        jdbc.sql("""
            INSERT INTO reference.universe_snapshots(universe_id,effective_date,observed_at,source,
                source_checksum,import_mode,completeness_status,expected_member_count,imported_member_count)
            SELECT universe_id,'2026-09-01','2026-09-01T19:30:00Z','fixture',repeat('e',64),
                import_mode,'PARTIAL',2,0 FROM reference.universe_snapshots WHERE universe_snapshot_id=:id
            """).param("id",sp500).update();
        var before=new ScoringInputRequest(DAY,CLOSE,Instant.parse("2026-09-01T19:15:00Z"),
            sp500,nasdaq,dataset,dataset,DAY,classification,"sec-us-gaap-v1",Set.of("TOTAL_ASSETS"));
        assertThat(repository.load(before).inputs()).hasSize(1);
        assertThatThrownBy(()->repository.load(request())).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SUPERSEDED_UNIVERSE_SNAPSHOT");
    }

    @Test void calendarHolesFailInsteadOfChangingTradingOffsets() {
        jdbc.sql("DELETE FROM reference.trading_sessions WHERE exchange_mic='XNYS' AND session_date='2026-08-03'").update();
        assertThatThrownBy(()->repository.load(request())).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INCOMPLETE_TRADING_CALENDAR");
    }

    @Test void conflictingPrimaryFlagsAndUnsupportedProfilesRemainVisible() {
        jdbc.sql("UPDATE reference.universe_memberships SET primary_liquid_class=false WHERE universe_snapshot_id=:id")
            .param("id",nasdaq).update();
        jdbc.sql("UPDATE fundamentals.profile_observations SET profile='BANK',status='MODEL_NOT_SUPPORTED'").update();
        var result=repository.load(request());
        assertThat(result.inputs()).hasSize(1);
        assertThat(result.excludedCount()).isEqualTo(1);
        assertThat(result.inputs().getFirst().reasons()).extracting(ScoringInput.Reason::code)
            .contains("NON_PRIMARY_SHARE_CLASS","MODEL_NOT_SUPPORTED");
    }

    @Test void staleFactsAndSharesCannotCertifyReadiness() {
        jdbc.sql("""
            UPDATE fundamentals.filings SET fiscal_period_end='2025-12-31',filed_date='2026-02-01',
                accepted_at='2026-02-01',published_at='2026-02-01'
            """).update();
        jdbc.sql("UPDATE fundamentals.fundamental_facts SET period_end='2025-12-31'").update();
        assertThat(repository.load(request()).inputs().getFirst().reasons()).extracting(ScoringInput.Reason::code)
            .contains("STALE_SHARES","STALE_OR_MISSING_FILING","MISSING_REQUIRED_METRIC");
    }
    @Test @Timeout(180) void targetScaleUsesOneStatementAndRecordsAnActualQueryPlan() throws Exception {
        jdbc.sql("INSERT INTO reference.issuers(legal_name) SELECT 'Scale-'||n FROM generate_series(2,600) n").update();
        jdbc.sql("""
            INSERT INTO reference.instruments(issuer_id,security_type,share_class,currency,primary_exchange_mic,valid_from)
            SELECT issuer_id,'COMMON_STOCK','A','USD','XNAS','2025-01-01' FROM reference.issuers
            WHERE legal_name LIKE 'Scale-%'
            """).update();
        jdbc.sql("""
            INSERT INTO reference.instrument_symbols(instrument_id,symbol,exchange_mic,effective_from,source,available_at,observed_at)
            SELECT i.instrument_id,upper(r.legal_name),'XNAS','2025-01-01','fixture','2025-01-01','2025-01-01'
            FROM reference.instruments i JOIN reference.issuers r USING(issuer_id) WHERE r.legal_name LIKE 'Scale-%'
            """).update();
        jdbc.sql("""
            INSERT INTO reference.universe_memberships(universe_snapshot_id,instrument_id,source_symbol,source_exchange_mic)
            SELECT s.universe_snapshot_id,i.instrument_id,upper(r.legal_name),'XNAS'
            FROM reference.instruments i JOIN reference.issuers r USING(issuer_id)
            CROSS JOIN reference.universe_snapshots s JOIN reference.universes u USING(universe_id)
            WHERE r.legal_name LIKE 'Scale-%' AND
                ((u.code='SP500' AND split_part(r.legal_name,'-',2)::int<=500)
                 OR (u.code='NASDAQ100' AND split_part(r.legal_name,'-',2)::int>500))
            """).update();
        jdbc.sql("""
            UPDATE reference.universe_snapshots s SET expected_member_count=m.n,imported_member_count=m.n
            FROM (SELECT universe_snapshot_id,count(*) n FROM reference.universe_memberships GROUP BY universe_snapshot_id) m
            WHERE s.universe_snapshot_id=m.universe_snapshot_id
            """).update();
        seed();
        jdbc.sql("""
            INSERT INTO fundamentals.filings(issuer_id,accession,revision_hash,form,fiscal_period_end,filed_date,
                accepted_at,published_at,available_at,observed_at,primary_document,parser_version,
                mapping_version,source_artifact_id,observation_key)
            SELECT issuer_id,accession||'-history-'||q,revision_hash,'10-Q',
                (fiscal_period_end-q*interval '3 months')::date,
                (filed_date-q*interval '3 months')::date,
                accepted_at-q*interval '3 months',published_at-q*interval '3 months',
                available_at-q*interval '3 months',observed_at-q*interval '3 months',
                primary_document,parser_version,mapping_version,source_artifact_id,observation_key
            FROM fundamentals.filings CROSS JOIN generate_series(1,12) q
            """).update();
        jdbc.sql("""
            INSERT INTO fundamentals.fundamental_facts(issuer_id,filing_id,metric_code,mapping_version,taxonomy,
                source_concept,period_end,source_value,numeric_value,unit,dimensions,source_context,source_fact_hash,
                priority,quality_state,available_at,observed_at,source_artifact_id)
            SELECT l.issuer_id,l.filing_id,m.code,l.mapping_version,'us-gaap',m.concept,l.fiscal_period_end,
                1000,1000,m.unit,'{}','{}',md5(m.code)||md5(m.code),1,'VALID',
                l.available_at,l.observed_at,l.source_artifact_id
            FROM fundamentals.filings l CROSS JOIN (VALUES ('TOTAL_ASSETS','Assets','USD'),
                ('SHARES_OUTSTANDING','CommonStockSharesOutstanding','shares')) m(code,concept,unit)
            WHERE l.accession LIKE '%-history-%'
            """).update();
        jdbc.sql("ANALYZE").update();
        DataSource counted=mock(DataSource.class);
        var connection=spy(source.getConnection());
        var statements=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            statements.incrementAndGet();
            return call.callRealMethod();
        }).when(connection).prepareStatement(anyString());
        when(counted.getConnection()).thenReturn(connection);
        var measured=new ScoringInputRepository(counted);
        var result=measured.load(request());
        verify(counted,times(1)).getConnection();
        assertThat(statements.get()).isEqualTo(1);
        assertThat(result.inputs()).hasSize(600);
        assertThat(result.readyCount()).isEqualTo(600);
        assertThat(result.inputs()).allSatisfy(row -> assertThat(row.facts()).hasSize(26));
        String plan=repository.explain(request());
        assertThat(plan).contains("Execution Time:","Buffers:");
        Files.createDirectories(Path.of("build/reports/scoring-inputs"));
        Files.writeString(Path.of("build/reports/scoring-inputs/explain.txt"),plan);
    }
}
