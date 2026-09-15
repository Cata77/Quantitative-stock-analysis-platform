package com.quantplatform.scoring.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.quantplatform.ingestion.*;
import com.quantplatform.marketdata.config.*;
import com.quantplatform.marketdata.fundamentals.*;
import com.quantplatform.marketdata.operations.*;
import com.quantplatform.marketdata.provider.alpaca.*;
import com.quantplatform.marketdata.provider.alphavantage.AlphaVantageFundamentalClient;
import com.quantplatform.scoring.fundamentals.PointInTimeFundamentals;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class FundamentalsIntegrationTest extends DurableDeliveryFixture {
    static final String CIK="0000320193";
    static final LocalDate RUN_DAY=LocalDate.parse("2026-09-01");
    UUID issuer;
    int sequence;
    PointInTimeFundamentals queries;
    @BeforeEach void fundamentals() {
        issuer=jdbc.sql("SELECT issuer_id FROM reference.instruments WHERE instrument_id=:id")
            .param("id",instrument).query(UUID.class).single();
        jdbc.sql("UPDATE reference.issuers SET cik=:cik WHERE issuer_id=:id").param("cik",CIK).param("id",issuer).update();
        jdbc.sql("UPDATE operations.job_definitions SET configuration='{\"eventType\":\"FILING_FACTS\"}' WHERE job_definition_id=:id")
            .param("id",job).update();
        queries=new PointInTimeFundamentals(source);
    }

    @Test void reproducesAppleAnnualAndFiscalWeekAlignedTtmWithFullLineage() {
        // Actual reported values/dates: Apple 2023 10-K and 2024 Q3 10-Q.
        var annual=filing("0000320193-23-000106","10-K","2023-09-30","2023-11-03","3571",null,List.of(
            fact("RevenueFromContractWithCustomerExcludingAssessedTax","2022-09-25","2023-09-30","383285000000","USD"),
            fact("NetCashProvidedByUsedInOperatingActivities","2022-09-25","2023-09-30","110543000000","USD"),
            fact("PaymentsToAcquirePropertyPlantAndEquipment","2022-09-25","2023-09-30","10959000000","USD")));
        stage(annual,"2023-11-04T12:00:00Z");
        acceptStaged();
        assertThat(queries.annual(issuer,"REVENUE",LocalDate.parse("2023-09-30"),Instant.parse("2024-01-01T00:00:00Z")).value())
            .isEqualByComparingTo("383285000000");
        var quarter=filing("0000320193-24-000081","10-Q","2024-06-29","2024-08-02","3571",null,List.of(
            fact("RevenueFromContractWithCustomerExcludingAssessedTax","2023-10-01","2024-06-29","296105000000","USD"),
            fact("RevenueFromContractWithCustomerExcludingAssessedTax","2022-09-25","2023-07-01","293787000000","USD")));
        stage(quarter,"2024-08-03T12:00:00Z");acceptStaged();
        var before=queries.trailingTwelveMonths(issuer,"REVENUE",LocalDate.parse("2024-06-29"),Instant.parse("2024-08-02T23:00:00Z"));
        assertThat(before.value()).isNull();
        var result=queries.trailingTwelveMonths(issuer,"REVENUE",LocalDate.parse("2024-06-29"),Instant.parse("2024-08-04T00:00:00Z"));
        assertThat(result.value()).isEqualByComparingTo("385603000000");
        assertThat(result.sourceFactIds()).hasSize(3);
        assertThat(result.availableAt()).isEqualTo(Instant.parse("2024-08-03T12:00:00Z"));
        assertThat(scalar("""
            SELECT COUNT(*) FROM fundamentals.fundamental_facts f JOIN fundamentals.filings l USING (filing_id)
            JOIN operations.source_artifacts a ON a.source_artifact_id=f.source_artifact_id
            WHERE f.mapping_version='sec-us-gaap-v1' AND l.parser_version='sec-companyfacts-v1'
            """)).isEqualTo(5);
        verifyNoInteractions(projection);
    }

    @Test void amendmentsAndSameAccessionCorrectionsNeverRewriteOlderCutoffs() {
        var original=filing("0000320193-26-000001","10-K","2025-12-31","2026-02-01","3571",null,
            List.of(fact("NetIncomeLoss","2025-01-01","2025-12-31","-10","USD")));
        stage(original,"2026-02-02T12:00:00Z");acceptStaged();
        var correction=filing(original.accession(),"10-K","2025-12-31","2026-02-01","3571",null,
            List.of(fact("NetIncomeLoss","2025-01-01","2025-12-31","-8","USD")));
        stage(correction,"2026-02-05T12:00:00Z");acceptStaged();
        var amendment=filing("0000320193-26-000002","10-K/A","2025-12-31","2026-02-10","3571",original.accession(),
            List.of(fact("NetIncomeLoss","2025-01-01","2025-12-31","-5","USD")));
        stage(amendment,"2026-02-11T12:00:00Z");acceptStaged();acceptStaged();
        assertThat(annual("NET_INCOME","2026-02-03T00:00:00Z")).isEqualByComparingTo("-10");
        assertThat(annual("NET_INCOME","2026-02-06T00:00:00Z")).isEqualByComparingTo("-8");
        assertThat(annual("NET_INCOME","2026-02-12T00:00:00Z")).isEqualByComparingTo("-5");
        assertThat(count("fundamentals.filings")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT amends_accession FROM fundamentals.filings WHERE form='10-K/A'")
            .query(String.class).single()).isEqualTo(original.accession());
    }

    @Test void invalidUnitsDimensionsAndConflictingFactsCannotLeakIntoCanonicalQueries() {
        var dimensioned=new FilingFacts.Fact("us-gaap","Assets",null,LocalDate.parse("2025-12-31"),"USD",
            new BigDecimal("100"),Map.of("segment","US"),Map.of());
        stage(filing("0000320193-26-000003","10-K","2025-12-31","2026-02-01","3571",null,List.of(
            fact("NetIncomeLoss","2025-01-01","2025-12-31","10","EUR"),dimensioned,
            fact("Revenues","2025-01-01","2025-12-31","100","USD"),
            fact("Revenues","2025-01-01","2025-12-31","101","USD"),
            fact("PaymentsToAcquirePropertyPlantAndEquipment","2025-01-01","2025-12-31","-1","USD"))),"2026-02-02T12:00:00Z");
        acceptStaged();
        assertThat(queries.load(issuer,Instant.parse("2026-02-03T00:00:00Z"))).isEmpty();
        assertThat(count("fundamentals.fundamental_facts")).isEqualTo(5);
        assertThat(scalar("SELECT COUNT(*) FROM fundamentals.fundamental_facts WHERE quality_state='UNSUPPORTED_DIMENSIONS'")).isEqualTo(1);
    }

    @Test void ttmRequiresContiguousQuartersAndDoesNotAddWeightedAverageShares() {
        var facts=new ArrayList<FilingFacts.Fact>();
        facts.add(fact("Revenues","2025-10-01","2025-12-31","10","USD"));
        facts.add(fact("Revenues","2026-01-01","2026-03-31","20","USD"));
        facts.add(fact("Revenues","2026-04-01","2026-06-30","30","USD"));
        facts.add(fact("Revenues","2026-07-01","2026-09-30","40","USD"));
        facts.add(fact("WeightedAverageNumberOfDilutedSharesOutstanding","2025-10-01","2026-09-30","100","shares"));
        stage(filing("0000320193-26-000004","10-K","2026-09-30","2026-11-01","3571",null,facts),"2026-11-02T12:00:00Z");
        acceptStaged();
        var cutoff=Instant.parse("2026-11-03T00:00:00Z");
        assertThat(queries.trailingTwelveMonths(issuer,"REVENUE",LocalDate.parse("2026-09-30"),cutoff).value()).isEqualByComparingTo("100");
        assertThat(queries.trailingTwelveMonths(issuer,"DILUTED_WEIGHTED_SHARES",LocalDate.parse("2026-09-30"),cutoff).reason())
            .isEqualTo("NON_ADDITIVE_METRIC");
        assertThat(queries.trailingTwelveMonths(issuer,"REVENUE",LocalDate.parse("2026-06-30"),cutoff).value()).isNull();
        assertThat(count("fundamentals.instrument_share_facts")).isZero();
    }

    @Test void issuerWideSharesAreNotAssignedToEachShareClass() {
        addShareClass();
        var filing=filing("0000320193-26-000005","10-Q","2026-08-31","2026-09-01","3571",null,
            List.of(fact("CommonStockSharesOutstanding",null,"2026-08-31","100","shares")));
        stage(filing,"2026-09-02T12:00:00Z");acceptStaged();
        assertThat(count("fundamentals.instrument_share_facts")).isZero();
        assertThat(count("fundamentals.fundamental_facts")).isEqualTo(1);
    }

    @Test void repeatedFilingsAcrossShareClassesDeduplicateAtIssuerLevel() {
        UUID other=addShareClass();
        var filing=filing("0000320193-26-000006","10-Q","2026-08-31","2026-09-01","3571",null,
            List.of(fact("Assets",null,"2026-08-31","100","USD")));
        String payload=CanonicalJson.MAPPER.writeValueAsString(filing);
        var run=jobs.plan(new IngestionPlan(job,sp500,nasdaq,RUN_DAY,RUN_DAY,"backfill","both","test"));
        for(var lease:jobs.claim(run,"fixture",10,Duration.ofMinutes(2))) {
            jobs.stage(lease,new SourceArtifact("both","test://sec/both",Instant.parse("2026-09-02T12:00:00Z"),
                "sec-companyfacts-v1",payload),List.of(new OutboxObservation(topic,"FILING_FACTS",
                filing.fiscalPeriodEnd().atStartOfDay(ZoneOffset.UTC).toInstant(),"NONE",payload)),"{}",true);
        }
        acceptStaged();
        assertThat(count("fundamentals.filings")).isEqualTo(1);
        assertThat(count("operations.kafka_inbox")).isEqualTo(2);
        assertThat(count("fundamentals.fundamental_facts")).isEqualTo(1);
        assertThat(other).isNotEqualTo(instrument);
    }

    @Test void insurerAndReitProfilesRemainExplicitlyUnsupported() {
        stage(filing("0000320193-26-000007","10-K","2025-12-31","2026-02-01","6311",null,
            List.of(fact("Assets",null,"2025-12-31","100","USD"))),"2026-02-02T12:00:00Z");
        stage(filing("0000320193-26-000008","10-Q","2026-03-31","2026-05-01","6798",null,
            List.of(fact("Assets",null,"2026-03-31","110","USD"))),"2026-05-02T12:00:00Z");
        acceptStaged();
        assertThat(scalar("SELECT COUNT(*) FROM fundamentals.profile_observations WHERE status='MODEL_NOT_SUPPORTED'")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT profile FROM fundamentals.profile_observations ORDER BY available_at").query(String.class).list())
            .containsExactly("INSURER","EQUITY_REIT");
        assertThat(count("fundamentals.fundamental_facts")).isEqualTo(2);
    }

    @Test void bankEntityLinksAndReportedApproachesRemainSeparateAndPointInTime() {
        var subsidiary=report("852218","SUBSIDIARY_ONLY",new BigDecimal("0.15"));
        stageReport(subsidiary,"2026-08-16T12:00:00Z");acceptStaged();
        assertThat(regulatoryCount("2026-08-17T00:00:00Z")).isZero();
        var bank=report("123456","SAME_LEGAL_ENTITY",new BigDecimal("0.13"));
        stageReport(bank,"2026-08-18T12:00:00Z");acceptStaged();
        assertThat(regulatoryCount("2026-08-17T00:00:00Z")).isZero();
        assertThat(regulatoryCount("2026-08-19T00:00:00Z")).isEqualTo(2);
        assertThat(jdbc.sql("""
            SELECT numeric_value FROM fundamentals.regulatory_facts_as_of(:issuer,:cutoff) WHERE metric_code='CET1_RATIO'
            """).param("issuer",issuer).param("cutoff",OffsetDateTime.parse("2026-08-19T00:00:00Z"))
            .query(BigDecimal.class).single()).isEqualByComparingTo("0.13");
        assertThat(count("fundamentals.regulated_entities")).isEqualTo(2);
    }

    @Test void issuerMismatchRollsBackTheCanonicalJournalAndInbox() {
        var good=filing("0000320193-26-000009","10-K","2025-12-31","2026-02-01","3571",null,
            List.of(fact("Assets",null,"2025-12-31","100","USD")));
        var wrong=new FilingFacts("0000000001",good.accession(),good.form(),good.filedDate(),good.acceptedAt(),
            good.fiscalPeriodEnd(),null,good.primaryDocument(),good.sic(),good.mappingVersion(),good.facts());
        stage(wrong,"2026-02-02T12:00:00Z");
        assertThatThrownBy(this::acceptStaged).isInstanceOf(MarketDataValidationException.class);
        assertThat(count("fundamentals.filings")).isZero();
        assertThat(count("market_data.observations")).isZero();
        assertThat(count("operations.kafka_inbox")).isZero();
    }

    @Test void secOnlyRuntimeCompletesWithoutAnAlpacaCalendarOrRepeatFetch() {
        var settings=new IngestionProperties("catch-up-and-serve",RUN_DAY,null,null,null,false,100,3,
            Duration.ofMinutes(2),Duration.ofSeconds(5),Duration.ofSeconds(10),"https://paper-api.alpaca.markets",group);
        var properties=new FundamentalProperties(true,URI.create("https://data.sec.test"),"Quant developer@example.com",
            Duration.ofMillis(200),LocalDate.of(2023,1,1),40,Duration.ofSeconds(5),"","");
        var fixed=clock("2026-09-15T12:00:00Z");
        var client=mock(SecDataClient.class);
        var catalog=Map.<String,Object>of("accessionNumber",List.of("0000320193-23-000106"),
            "filingDate",List.of("2023-11-03"),"reportDate",List.of("2023-09-30"),
            "acceptanceDateTime",List.of("2023-11-03T12:00:00Z"),"form",List.of("10-K"),"primaryDocument",List.of("aapl.htm"));
        var company=Map.<String,Object>of("cik",320193,"facts",Map.of("us-gaap",Map.of("Revenues",Map.of("units",Map.of("USD",List.of(
            Map.of("accn","0000320193-23-000106","filed","2023-11-03","form","10-K",
                "start","2022-09-25","end","2023-09-30","val",383285000000L)))))));
        when(client.fetch(anyString(),any(),any())).thenReturn(new SecDataClient.Bundle(company,List.of(catalog),"3571",
            Map.of("test://companyfacts",CanonicalJson.write(company)),fixed.instant()));
        var collector=new SecFilingsCollector(source,client,properties,settings,jobs,fixed);
        var calendar=mock(TradingCalendarLoader.class);
        var coordinator=new IngestionCoordinator(source,tx,jobs,calendar,mock(AlpacaStockMarketClient.class),
            mock(AlphaVantageFundamentalClient.class),new MarketDataProperties(true,List.of("IGNORED"),topic,1,
                Duration.ofSeconds(10),Duration.ofSeconds(10),false,false,1,"1Day",false),
            new AlpacaProperties(URI.create("https://data.alpaca.markets"),"","","sip"),settings,fixed,
            mock(BulkDailyCollector.class),new DailyPriceProperties("sip",100,10000,Duration.ofMillis(350),Duration.ofMinutes(16),true,true,14));
        coordinator.fundamentalCollectors(collector,null);
        assertThat(coordinator.reconcile().state()).isEqualTo("SYNCING");
        acceptStaged();
        assertThat(coordinator.reconcile().state()).isEqualTo("READY");
        assertThat(count("fundamentals.filings")).isEqualTo(1);
        verify(client,times(1)).fetch(anyString(),any(),any());verifyNoInteractions(calendar);
    }

    @Test void invalidBankCorrectionWithdrawsTheOldValueOnlyAfterItsCutoff() {
        stageReport(report("123456","SAME_LEGAL_ENTITY",new BigDecimal("0.13")),"2026-08-18T12:00:00Z");
        acceptStaged();
        stageReport(report("123456","SAME_LEGAL_ENTITY",new BigDecimal("-0.13")),"2026-08-20T12:00:00Z");
        acceptStaged();
        assertThat(regulatoryCount("2026-08-19T00:00:00Z")).isEqualTo(2);
        assertThat(regulatoryCount("2026-08-21T00:00:00Z")).isZero();
        assertThat(count("fundamentals.regulatory_facts")).isEqualTo(4);
    }

    @Test void interruptedFilingCollectionResumesTheCachedSourceWithoutAnotherProviderRequest() {
        var properties=new FundamentalProperties(true,URI.create("https://data.sec.test"),"Quant developer@example.com",
            Duration.ofMillis(200),LocalDate.of(2023,1,1),40,Duration.ofSeconds(5),"","");
        var settings=new IngestionProperties("backfill",RUN_DAY,RUN_DAY,null,null,false,100,3,
            Duration.ofMinutes(2),Duration.ofSeconds(1),Duration.ofSeconds(10),"https://paper-api.alpaca.markets",group);
        var client=mock(SecDataClient.class);
        var catalog=Map.<String,Object>of("accessionNumber",List.of("0000320193-23-000106","0000320193-24-000081"),
            "filingDate",List.of("2023-11-03","2024-08-02"),"reportDate",List.of("2023-09-30","2024-06-29"),
            "acceptanceDateTime",List.of("2023-11-03T12:00:00Z","2024-08-02T12:00:00Z"),
            "form",List.of("10-K","10-Q"),"primaryDocument",List.of("annual.htm","quarter.htm"));
        var company=Map.<String,Object>of("cik",320193,"facts",Map.of("us-gaap",Map.of("Revenues",Map.of("units",Map.of("USD",List.of(
            Map.of("accn","0000320193-23-000106","filed","2023-11-03","form","10-K",
                "start","2022-09-25","end","2023-09-30","val",383285000000L),
            Map.of("accn","0000320193-24-000081","filed","2024-08-02","form","10-Q",
                "start","2023-10-01","end","2024-06-29","val",296105000000L)))))));
        when(client.fetch(anyString(),any(),any())).thenReturn(new SecDataClient.Bundle(company,List.of(catalog),"3571",
            Map.of("test://companyfacts",CanonicalJson.write(company)),Instant.parse("2026-09-02T12:00:00Z")));
        var durable=spy(jobs);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{
            if(calls.incrementAndGet()==2)throw new IllegalStateException("simulated interruption between filings");
            return call.callRealMethod();
        }).when(durable).stage(any(),any(),anyList(),anyString(),anyBoolean());
        var collector=new SecFilingsCollector(source,client,properties,settings,durable,clock("2026-09-02T12:00:00Z"));
        var run=jobs.plan(new IngestionPlan(job,sp500,nasdaq,RUN_DAY,RUN_DAY,"backfill","resume","test"));
        collector.collect(jobs.claim(run,"first",1,Duration.ofMinutes(2)).getFirst(),topic);
        assertThat(count("operations.outbox_events")).isEqualTo(1);
        assertThat(text("SELECT status FROM operations.ingestion_run_items")).isEqualTo("WAITING_RETRY");
        jdbc.sql("UPDATE operations.ingestion_run_items SET next_retry_at=clock_timestamp()").update();
        collector.collect(jobs.claim(run,"restart",1,Duration.ofMinutes(2)).getFirst(),topic);
        acceptStaged();
        assertThat(count("fundamentals.filings")).isEqualTo(2);
        assertThat(text("SELECT status FROM operations.ingestion_run_items")).isEqualTo("COMPLETE");
        verify(client,times(1)).fetch(anyString(),any(),any());
    }

    @Test void sameAccessionRemovalWithdrawsFactsOnlyAfterTheCorrectionIsObserved() {
        String accession="0000320193-26-000020";
        stage(filing(accession,"10-K","2025-12-31","2026-02-01","3571",null,
            List.of(fact("NetIncomeLoss","2025-01-01","2025-12-31","10","USD"))),"2026-02-02T12:00:00Z");
        acceptStaged();
        stage(filing(accession,"10-K","2025-12-31","2026-02-01","3571",null,List.of()),"2026-02-05T12:00:00Z");
        acceptStaged();
        assertThat(annual("NET_INCOME","2026-02-03T00:00:00Z")).isEqualByComparingTo("10");
        assertThat(annual("NET_INCOME","2026-02-06T00:00:00Z")).isNull();
        assertThat(count("fundamentals.fundamental_facts")).isEqualTo(1);
    }

    @Test void regulatoryCorrectionCannotRetainRemovedApproachesOrOldParentScope() {
        var original=report("123456","SAME_LEGAL_ENTITY",new BigDecimal("0.13"));
        stageReport(original,"2026-08-18T12:00:00Z");acceptStaged();
        var corrected=new RegulatoryReport(original.cik(),original.rssd(),original.legalName(),original.periodEnd(),
            original.availableAt(),original.effectiveFrom(),null,"SUBSIDIARY_ONLY",original.evidenceUri(),
            original.mappingVersion(),Map.of("RCOAP793",new BigDecimal("0.12")));
        stageReport(corrected,"2026-08-20T12:00:00Z");acceptStaged();
        assertThat(regulatoryCount("2026-08-19T00:00:00Z")).isEqualTo(2);
        assertThat(regulatoryCount("2026-08-21T00:00:00Z")).isZero();
        assertThat(count("fundamentals.regulatory_facts")).isEqualTo(3);
    }

    @Test void whollyInvalidFactsDoNotAdvertiseAvailableProfileInputs() {
        stage(filing("0000320193-26-000021","10-K","2025-12-31","2026-02-01","3571",null,
            List.of(fact("NetIncomeLoss","2025-01-01","2025-12-31","10","EUR"))),"2026-02-02T12:00:00Z");
        acceptStaged();
        assertThat(text("SELECT reason FROM fundamentals.profile_observations")).isEqualTo("NO_MAPPED_FACTS");
        assertThat(queries.load(issuer,Instant.parse("2026-02-03T00:00:00Z"))).isEmpty();
    }

    private BigDecimal annual(String metric,String cutoff) {
        return queries.annual(issuer,metric,LocalDate.of(2025,12,31),Instant.parse(cutoff)).value();
    }
    private int regulatoryCount(String cutoff) {
        return jdbc.sql("SELECT COUNT(*) FROM fundamentals.regulatory_facts_as_of(:issuer,:cutoff)")
            .param("issuer",issuer).param("cutoff",OffsetDateTime.parse(cutoff)).query(Integer.class).single();
    }
    private RegulatoryReport report(String rssd,String scope,BigDecimal value) {
        return new RegulatoryReport(CIK,rssd,"Fixture bank",LocalDate.of(2026,6,30),Instant.parse("2026-08-15T12:00:00Z"),
            LocalDate.of(2026,1,1),null,scope,"https://example.com/verified-fixture-link","ffiec-call-v1",
            Map.of("RCOAP793",value,"RCOWP793",value.subtract(new BigDecimal("0.01"))));
    }
    private FilingFacts filing(String accession,String form,String period,String filed,String sic,String amends,List<FilingFacts.Fact> facts) {
        return new FilingFacts(CIK,accession,form,LocalDate.parse(filed),Instant.parse(filed+"T12:00:00Z"),
            LocalDate.parse(period),amends,"fixture.htm",sic,"sec-us-gaap-v1",facts);
    }
    private FilingFacts.Fact fact(String concept,String start,String end,String value,String unit) {
        return new FilingFacts.Fact("us-gaap",concept,start==null?null:LocalDate.parse(start),LocalDate.parse(end),
            unit,new BigDecimal(value),Map.of(),Map.of("fixture",true));
    }
    private void stage(FilingFacts filing,String observed) {
        stagePayload("FILING_FACTS",filing.fiscalPeriodEnd(),CanonicalJson.MAPPER.writeValueAsString(filing),observed);
    }
    private void stageReport(RegulatoryReport report,String observed) {
        stagePayload("REGULATORY_FACTS",report.periodEnd(),CanonicalJson.MAPPER.writeValueAsString(report),observed);
    }
    private void stagePayload(String type,LocalDate period,String payload,String observed) {
        String request="fundamentals-"+(++sequence);
        var run=jobs.plan(new IngestionPlan(job,sp500,nasdaq,RUN_DAY,RUN_DAY,"backfill",request,"test"));
        var lease=jobs.claim(run,"fixture",1,Duration.ofMinutes(2)).getFirst();
        jobs.stage(lease,new SourceArtifact(request,"test://filing/"+request,Instant.parse(observed),"sec-companyfacts-v1",payload),
            List.of(new OutboxObservation(topic,type,period.atStartOfDay(ZoneOffset.UTC).toInstant(),"NONE",payload)),"{}",true);
    }
    private UUID addShareClass() {
        UUID other=jdbc.sql("""
            INSERT INTO reference.instruments (issuer_id,security_type,share_class,currency,primary_exchange_mic,valid_from)
            VALUES (:issuer,'COMMON_STOCK','B','USD','XNAS','2026-08-01') RETURNING instrument_id
            """).param("issuer",issuer).query(UUID.class).single();
        for(UUID snapshot:List.of(sp500,nasdaq)) {
            jdbc.sql("""
                INSERT INTO reference.universe_memberships (universe_snapshot_id,instrument_id,source_symbol,source_exchange_mic,primary_liquid_class)
                VALUES (:snapshot,:id,'OTHER','XNAS',false)
                """).param("snapshot",snapshot).param("id",other).update();
            jdbc.sql("UPDATE reference.universe_snapshots SET expected_member_count=2,imported_member_count=2 WHERE universe_snapshot_id=:id")
                .param("id",snapshot).update();
        }
        return other;
    }
}
