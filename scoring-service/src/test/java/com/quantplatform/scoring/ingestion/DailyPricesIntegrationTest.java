package com.quantplatform.scoring.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.quantplatform.ingestion.*;
import com.quantplatform.marketdata.operations.*;
import com.quantplatform.marketdata.provider.alpaca.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DailyPricesIntegrationTest extends DurableDeliveryFixture {
    static final LocalDate DAY = LocalDate.parse("2026-09-01");

    @Test void bulkPaginationDeduplicatesAndRestartUsesPersistedCheckpoint() {
        calendar(DAY,DAY);
        dailyJob("RAW");
        UUID second = secondInstrument();
        var run = plan("pages");
        var client = mock(AlpacaDailyDataClient.class);
        var raw = price(DAY,"100",null);
        when(client.bars(anyList(),eq(DAY),eq("RAW"),isNull(),eq("")))
                .thenReturn(page(Map.of("FIX",List.of(raw)),"p2"));
        when(client.bars(anyList(),eq(DAY),eq("RAW"),isNull(),eq("p2")))
                .thenThrow(new IllegalStateException("provider temporarily unavailable"));
        collector(client).collect(jobs.claim(run,"first",100,Duration.ofMinutes(2)),"DAILY_PRICE",topic);
        assertThat(count("operations.outbox_events")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM operations.ingestion_run_items WHERE status='WAITING_RETRY'")).isEqualTo(2);
        jdbc.sql("UPDATE operations.ingestion_run_items SET next_retry_at=clock_timestamp()").update();
        when(client.bars(anyList(),eq(DAY),eq("RAW"),isNull(),eq("p2")))
                .thenReturn(page(Map.of("FIX",List.of(raw),"SECOND",List.of(raw)),""));
        collector(client).collect(jobs.claim(run,"restart",100,Duration.ofMinutes(2)),"DAILY_PRICE",topic);
        acceptStaged();
        acceptStaged();
        assertThat(count("market_data.daily_bar_observations")).isEqualTo(2);
        assertThat(count("operations.outbox_events")).isEqualTo(2);
        assertThat(text("SELECT status FROM operations.ingestion_runs WHERE ingestion_run_id='"+run+"'")).isEqualTo("COMPLETE");
        verify(client,times(1)).bars(eq(List.of("FIX","SECOND")),eq(DAY),eq("RAW"),isNull(),eq(""));
        assertThat(second).isNotEqualTo(instrument);
    }

    @Test void repeatedTokenCannotCertifyAnUnfinishedPage() {
        calendar(DAY,DAY);
        dailyJob("RAW");
        var run = plan("loop");
        var client = mock(AlpacaDailyDataClient.class);
        when(client.bars(anyList(),any(),anyString(),any(),anyString()))
                .thenReturn(page(Map.of("FIX",List.of(price(DAY,"100",null))),"repeated"));
        collector(client).collect(jobs.claim(run,"first",100,Duration.ofMinutes(2)),"DAILY_PRICE",topic);
        acceptStaged();
        assertThat(count("market_data.daily_bar_observations")).isEqualTo(1);
        assertThat(count("operations.data_coverage")).isZero();
        assertThat(text("SELECT status FROM operations.ingestion_run_items")).isEqualTo("WAITING_RETRY");
        verify(client,times(2)).bars(anyList(),any(),anyString(),any(),anyString());
    }

    @Test void missingSessionBlocksCoverageAndLaterRetryRepairsGap() {
        calendar(DAY,DAY);
        dailyJob("RAW");
        var run = plan("gap");
        var client = mock(AlpacaDailyDataClient.class);
        when(client.bars(anyList(),any(),anyString(),any(),anyString())).thenReturn(page(Map.of(),""));
        collector(client).collect(jobs.claim(run,"first",100,Duration.ofMinutes(2)),"DAILY_PRICE",topic);
        assertThat(scalar("SELECT COUNT(*) FROM operations.data_quality_issues WHERE status='OPEN'")).isEqualTo(1);
        assertThat(count("operations.data_coverage")).isZero();
        jdbc.sql("UPDATE operations.ingestion_run_items SET next_retry_at=clock_timestamp()").update();
        when(client.bars(anyList(),any(),anyString(),any(),anyString()))
                .thenReturn(page(Map.of("FIX",List.of(price(DAY,"100",null))),""));
        collector(client).collect(jobs.claim(run,"retry",100,Duration.ofMinutes(2)),"DAILY_PRICE",topic);
        acceptStaged();
        assertThat(scalar("SELECT COUNT(*) FROM operations.data_quality_issues WHERE status='OPEN'")).isZero();
        assertThat(count("operations.data_coverage")).isEqualTo(1);
    }

    @Test void correctionsAppendAndCutoffKeepsPriorRevision() {
        calendar(DAY,DAY);
        dailyJob("RAW");
        stageDaily("original","100",null);
        acceptStaged();
        var cutoff = jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        stageDaily("correction","105",null);
        acceptStaged();
        assertThat(count("market_data.daily_bar_observations")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT close FROM market_data.latest_daily_bars").query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("105");
        assertThat(jdbc.sql("SELECT close FROM market_data.daily_bars_as_of(:dataset,'RAW',NULL,:cutoff)")
                .param("dataset",dataset).param("cutoff",cutoff).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("100");
        verifyNoInteractions(projection);
    }

    @Test void rawAndAdjustedSplitDividendFixturesStaySeparate() {
        calendar(DAY,DAY);
        dailyJob("RAW");
        stageDaily("raw","100",null);
        dailyJob("SPLIT_DIVIDEND");
        stageDaily("adjusted","49",DAY.plusDays(1));
        acceptStaged();
        assertThat(count("market_data.daily_bar_observations")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT close FROM market_data.latest_daily_bars WHERE adjustment_mode='RAW'")
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("100");
        assertThat(jdbc.sql("SELECT close FROM market_data.latest_daily_bars WHERE adjustment_mode='SPLIT_DIVIDEND'")
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("49");
        var rawClose = jdbc.sql("SELECT close FROM market_data.latest_daily_bars WHERE adjustment_mode='RAW'")
                .query(java.math.BigDecimal.class).single();
        var adjustedClose = jdbc.sql("SELECT close FROM market_data.latest_daily_bars WHERE adjustment_mode='SPLIT_DIVIDEND'")
                .query(java.math.BigDecimal.class).single();
        assertThat(rawClose.divide(new java.math.BigDecimal("2")).subtract(java.math.BigDecimal.ONE))
                .isEqualByComparingTo(adjustedClose);
        // A 2-for-1 split followed by a $1 distribution gives the declared 49 fixture.
        action("split",Map.of("type","forward_splits","symbol","FIX","ex_date",DAY.toString(),"old_rate",1,"new_rate",2));
        action("dividend",Map.of("type","cash_dividends","symbol","FIX","ex_date",DAY.toString(),"rate",1));
        acceptStaged();
        assertThat(scalar("SELECT COUNT(*) FROM market_data.corporate_actions WHERE quality_state='VALID'")).isEqualTo(2);
    }

    @Test void mergerSpinoffAndChangedSecurityBlockCoverageWithoutMergingIdentities() {
        calendar(DAY,DAY);
        secondInstrument();
        action("merger",Map.of("type","stock_mergers","acquiree_symbol","FIX","acquirer_symbol","SECOND",
                "effective_date",DAY.toString(),"acquiree_rate",1,"acquirer_rate",2));
        action("spin",Map.of("type","spin_offs","source_symbol","FIX","new_symbol","SECOND",
                "ex_date",DAY.toString(),"source_rate",1,"new_rate",1));
        action("security",Map.of("type","name_changes","old_symbol","FIX","new_symbol","NEW",
                "old_cusip","old","new_cusip","different"));
        acceptStaged();
        acceptStaged();
        assertThat(scalar("SELECT COUNT(*) FROM market_data.corporate_actions WHERE quality_state='REVIEW_REQUIRED'")).isEqualTo(3);
        assertThat(scalar("SELECT COUNT(*) FROM operations.data_quality_issues WHERE status='OPEN'")).isEqualTo(3);
        assertThat(scalar("SELECT COUNT(*) FROM reference.instrument_symbols WHERE symbol='FIX' AND effective_to IS NULL")).isEqualTo(1);
        assertThat(count("operations.data_coverage")).isZero();
    }

    @Test void symbolRenamePreservesIdentityAndReplayIsIdempotent() {
        calendar(DAY,DAY);
        action("rename",Map.of("type","name_changes","old_symbol","FIX","new_symbol","RENAMED",
                "old_cusip","same","new_cusip","same"));
        acceptStaged();
        acceptStaged();
        assertThat(jdbc.sql("SELECT instrument_id FROM reference.instrument_symbols WHERE symbol='RENAMED'")
                .query(UUID.class).single()).isEqualTo(instrument);
        assertThat(jdbc.sql("SELECT effective_to FROM reference.instrument_symbols WHERE symbol='FIX'")
                .query(LocalDate.class).single()).isEqualTo(DAY);
        assertThat(count("market_data.corporate_actions")).isEqualTo(1);
    }

    @Test void calendarRejectsHolidayBarAndRollsBackInbox() {
        calendar(DAY,DAY);
        dailyJob("RAW");
        stageDaily("holiday","100",null);
        jdbc.sql("UPDATE reference.trading_sessions SET holiday=true,opens_at=NULL,closes_at=NULL WHERE session_date=:day")
                .param("day",DAY).update();
        assertThatThrownBy(this::acceptStaged).isInstanceOf(MarketDataValidationException.class);
        assertThat(count("market_data.daily_bar_observations")).isZero();
        assertThat(count("operations.kafka_inbox")).isZero();
    }

    @Test void latestEligibleSessionRespectsNewYorkDayAndPublicationDelay() {
        calendar(DAY,DAY.plusDays(1));
        var alpaca = mock(AlpacaStockMarketClient.class);
        var before = coordinator(alpaca,"2026-09-02T04:10:00Z").reconcile();
        assertThat(before.completeThrough()).isNull();
        verifyNoInteractions(alpaca);
        when(alpaca.fetchDurablePage(anyString(),any(),any(),anyString()))
                .thenReturn(new AlpacaStockMarketClient.DurableBarPage(
                        List.of(new com.quantplatform.marketdata.event.StockBar(DAY.atTime(4,0).toInstant(ZoneOffset.UTC),
                            new java.math.BigDecimal("100"),new java.math.BigDecimal("110"),new java.math.BigDecimal("90"),
                            new java.math.BigDecimal("100"),1000,null,10)),"","{}","test://bar",Instant.parse("2026-09-02T05:00:00Z")));
        var after = coordinator(alpaca,"2026-09-02T04:17:00Z");
        assertThat(after.reconcile().latestEligibleSession()).isEqualTo(DAY);
        acceptStaged();
        assertThat(after.reconcile().completeThrough()).isEqualTo(DAY);
    }

    void dailyJob(String adjustment) {
        jdbc.sql("UPDATE operations.job_definitions SET configuration=CAST(:config AS jsonb) WHERE job_definition_id=:id")
                .param("id",job).param("config",CanonicalJson.write(adjustment.equals("RAW")
                    ? Map.of("eventType","DAILY_PRICE","adjustment",adjustment)
                    : Map.of("eventType","DAILY_PRICE","adjustment",adjustment,"adjustmentAsOf",DAY.plusDays(1).toString()))).update();
    }
    UUID plan(String request) { return jobs.plan(new IngestionPlan(job,sp500,nasdaq,DAY,DAY,"backfill",request,"test")); }
    void stageDaily(String request,String close,LocalDate vintage) {
        var lease=jobs.claim(plan(request),"fixture",1,Duration.ofMinutes(2)).getFirst();
        jobs.stage(lease,new SourceArtifact(request,"test://daily/"+request,Instant.now(),"fixture","{\"request\":\""+request+"\"}"),
            List.of(new OutboxObservation(topic,"DAILY_PRICE",DAY.atTime(4,0).toInstant(ZoneOffset.UTC),
                vintage==null?"RAW":"SPLIT_DIVIDEND",CanonicalJson.write(price(DAY,close,vintage)))),"{}",true);
    }
    void action(String id,Map<String,Object> terms) {
        jdbc.sql("UPDATE operations.job_definitions SET configuration='{\"eventType\":\"CORPORATE_ACTION_BATCH\"}' WHERE job_definition_id=:id")
                .param("id",job).update();
        var data=new TreeMap<>(terms); data.put("id",id);data.put("process_date",DAY.toString());
        var lease=jobs.claim(plan(id),"fixture",1,Duration.ofMinutes(2)).getFirst();
        jobs.stage(lease,new SourceArtifact(id,"test://action/"+id,Instant.now(),"fixture",CanonicalJson.write(data)),
            List.of(new OutboxObservation(topic,"CORPORATE_ACTION_BATCH",DAY.atStartOfDay(ZoneOffset.UTC).toInstant(),
                "NONE",CanonicalJson.write(Map.of("processDate",DAY.toString(),"actions",List.of(data))))),"{}",true);
    }
    Map<String,Object> price(LocalDate date,String close,LocalDate vintage) {
        var result=new TreeMap<String,Object>();
        result.put("time",date+"T04:00:00Z"); result.put("sessionDate",date.toString());
        result.put("currency","USD");result.put("feed","sip");
        result.put("open",close);result.put("high",close);result.put("low",close);result.put("close",close);
        result.put("volume",1000);result.put("tradeCount",10);
        if(vintage!=null)result.put("adjustmentAsOf",vintage.toString());
        return result;
    }
    AlpacaDailyDataClient.Page page(Map<String,List<Map<String,Object>>> values,String token) {
        return new AlpacaDailyDataClient.Page(values,token,CanonicalJson.write(values),"test://bulk/"+token,Instant.now());
    }
    BulkDailyCollector collector(AlpacaDailyDataClient client) {
        return new BulkDailyCollector(source,tx,jobs,client,new IngestionProperties("backfill",DAY,DAY,null,null,
            false,100,3,Duration.ofMinutes(2),Duration.ofMillis(1),Duration.ofSeconds(10),"test://calendar",group));
    }
    UUID secondInstrument() {
        UUID id=jdbc.sql("""
            INSERT INTO reference.instruments (issuer_id,security_type,share_class,currency,primary_exchange_mic,valid_from)
            SELECT issuer_id,'COMMON_STOCK','B','USD','XNAS','2026-08-01' FROM reference.instruments WHERE instrument_id=:id
            RETURNING instrument_id
            """).param("id",instrument).query(UUID.class).single();
        jdbc.sql("""
            INSERT INTO reference.instrument_symbols (instrument_id,symbol,exchange_mic,effective_from,source,available_at,observed_at)
            VALUES (:id,'SECOND','XNAS','2026-08-01','fixture','2026-08-01T00:00:00Z','2026-08-01T00:00:00Z')
            """).param("id",id).update();
        for(UUID snapshot:List.of(sp500,nasdaq)) {
            jdbc.sql("""
                INSERT INTO reference.universe_memberships (universe_snapshot_id,instrument_id,source_symbol,source_exchange_mic,primary_liquid_class)
                VALUES (:snapshot,:id,'SECOND','XNAS',false)
                """).param("snapshot",snapshot).param("id",id).update();
            jdbc.sql("UPDATE reference.universe_snapshots SET expected_member_count=2,imported_member_count=2 WHERE universe_snapshot_id=:id")
                .param("id",snapshot).update();
        }
        return id;
    }
}
