package com.quantplatform.screener;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.sql.*;
import com.quantplatform.screener.search.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(classes = com.quantplatform.web.WebBackendApplication.class, properties={"spring.sql.init.mode=never"})
@AutoConfigureMockMvc
@Transactional
@Sql("/publication-fixture.sql")
class ScreenerApplicationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(DockerImageName
        .parse("timescale/timescaledb:2.29.1-pg18").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE");
        }
        Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())
            .defaultSchema("operations").schemas("operations").locations("classpath:db/migration").load().migrate();
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired CompanySearchService search;
    static final java.util.UUID READER_ID=java.util.UUID.fromString("70000000-0000-0000-0000-000000000001");
    static final String READER_TOKEN=com.quantplatform.security.SecurityTokens.token(READER_ID);
    @Test void directRequestsRequireBearerAuthenticationAndReadScope() throws Exception {
        mvc.perform(get("/screener/rankings").header("X-Authenticated-User-Id",READER_ID))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/screener/rankings").header("Authorization",
            com.quantplatform.security.SecurityTokens.token(READER_ID,"portfolio:write")))
            .andExpect(status().isForbidden());
    }
    static final String RUN="50000000-0000-0000-0000-000000000002";

    @Test void latestPublicationRejectsPendingAndFailedRunsAndKeepsOriginalTieRanks() throws Exception {
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("asOf","2026-08-31").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.run.id").value(RUN))
            .andExpect(jsonPath("$.run.timingPolicy").value("CLOSE_V1"))
            .andExpect(jsonPath("$.run.publicationType").value("MONTH_END_RESEARCH"))
            .andExpect(jsonPath("$.run.complete").value(true)).andExpect(jsonPath("$.run.expectedCount").value(3))
            .andExpect(jsonPath("$.run.profileCoverage.GENERAL.scored").value(2))
            .andExpect(jsonPath("$.run.classificationVersionId").value("60000000-0000-0000-0000-000000000001"))
            .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.content[0].symbol").value("OLD1"))
            .andExpect(jsonPath("$.content[0].rank").value(1)).andExpect(jsonPath("$.content[0].factors.length()").value(7))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("12345678901234567890.123456789")));
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("asOf","2026-08-31").param("size","1").param("page","1").param("runId",RUN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].symbol").value("OLD2"))
            .andExpect(jsonPath("$.content[0].rank").value(2)).andExpect(jsonPath("$.content[0].percentile").value(50));
    }
    @Test void laterPublicationWinsButAnExplicitRunKeepsPaginationStable() throws Exception {
        String newer="50000000-0000-0000-0000-000000000005";
        jdbc.sql("""
            INSERT INTO research.scoring_runs(score_run_id,logical_key,as_of_date,market_cutoff,knowledge_cutoff,effective_from,
                sp500_snapshot_id,nasdaq100_snapshot_id,model_version_id,certification_id,candidate,input_version,request,supersedes)
            SELECT CAST(:id AS uuid),repeat('f',64),as_of_date,market_cutoff,knowledge_cutoff,effective_from,
                sp500_snapshot_id,nasdaq100_snapshot_id,model_version_id,certification_id,candidate,input_version,request,score_run_id
            FROM research.scoring_runs WHERE score_run_id=CAST(:old AS uuid)
            """).param("id",newer).param("old",RUN).update();
        for(String table:List.of("score_lineage","stock_scores","factor_observations","score_exclusions","score_source_artifacts")) {
            jdbc.sql("INSERT INTO research."+table+" SELECT (jsonb_populate_record(NULL::research."+table+
                ",to_jsonb(s)||jsonb_build_object('score_run_id',CAST(:id AS text)))).* FROM research."+table+
                " s WHERE score_run_id=CAST(:old AS uuid)").param("id",newer).param("old",RUN).update();
        }
        jdbc.sql("""
            UPDATE research.scoring_runs r SET input_document=o.input_document,input_sha256=o.input_sha256,
                expected_count=o.expected_count,scored_count=o.scored_count,eligible_count=o.eligible_count,
                excluded_count=o.excluded_count,state='PUBLISHED',published_at='2026-08-02'
            FROM research.scoring_runs o WHERE o.score_run_id=CAST(:old AS uuid) AND r.score_run_id=CAST(:id AS uuid)
            """).param("id",newer).param("old",RUN).update();
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("$.run.id").value(newer));
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("runId",RUN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.run.id").value(RUN));
    }
    @Test void filtersMembershipProfileWarningsAndSectorWithoutReranking() throws Exception {
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("asOf","2026-08-01").param("universe","NASDAQ100")
            .param("profile","GENERAL").param("sector","Technology").param("peerGroup","Technology").param("warning","NO_DISPERSION"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].rank").value(2));
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("universe","SP500").param("sector","Technology' OR true --"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void sortsFamiliesAndContributionsAndInspectsExclusions() throws Exception {
        for(String sort:List.of("VALUE","CONTRIBUTION")) {
            mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("sort",sort).param("metric","metric1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].symbol").value("OLD2"));
        }
        for(String sort:List.of("QUALITY","MOMENTUM")) {
            mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("sort",sort))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].symbol").value("OLD1"));
        }
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("direction","ASC"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].symbol").value("OLD2"));
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("eligibility","EXCLUDED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].eligible").value(false))
            .andExpect(jsonPath("$.content[0].rank").doesNotExist())
            .andExpect(jsonPath("$.content[0].exclusions[0].reason_code").value("MODEL_NOT_SUPPORTED"));
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("eligibility","ALL"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("eligibility","ALL").param("instrumentId","30000000-0000-0000-0000-000000000003"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].symbol").value("OLD3"));
    }
    @Test void datesModelSelectionAndFreshnessAreExplicit() throws Exception {
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("asOf","2026-07-15T00:00:00Z"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.run.asOfDate").value("2026-06-30"))
            .andExpect(jsonPath("$.freshness").value("CURRENT"));
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("asOf","2026-10-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.freshness").value("STALE"));
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("asOf","2026-05-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.freshness").value("UNAVAILABLE"));
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("modelVersion",UUID.randomUUID().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        String model=jdbc.sql("SELECT model_version_id::text FROM research.model_versions WHERE semantic_version='1.0.0'").query(String.class).single();
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("modelVersion",model))
            .andExpect(status().isOk()).andExpect(jsonPath("$.run.modelVersionId").value(model));
    }
    @Test void validatesQueriesAndNeverExposesAnUnpublishedPinnedRun() throws Exception {
        for(var pair:List.of(new String[]{"size","201"},new String[]{"asOf","bad"},new String[]{"universe","BAD"},
                new String[]{"sort","bad"},new String[]{"sort","CONTRIBUTION"},new String[]{"page","-1"}))
            mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param(pair[0],pair[1])).andExpect(status().isBadRequest());
        mvc.perform(get("/screener/search").header("Authorization",READER_TOKEN).param("q","   ")).andExpect(status().isBadRequest());
        mvc.perform(get("/screener/search").header("Authorization",READER_TOKEN).param("q","test").param("page","500").param("size","100"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/screener/rankings").header("Authorization",READER_TOKEN).param("runId","50000000-0000-0000-0000-000000000003"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.freshness").value("UNAVAILABLE"));
    }
    static final String FIRST="30000000-0000-0000-0000-000000000001";
    static final String EXCLUDED="30000000-0000-0000-0000-000000000003";
    static org.springframework.test.web.servlet.ResultActions financials(MockMvc mvc,String instrument,String run) throws Exception {
        var request=get("/screener/instruments/"+instrument+"/financials").header("Authorization",READER_TOKEN).param("view","FULL");
        return mvc.perform(run==null?request:request.param("runId",run));
    }
    @Test void financialsSeparateInputsDerivationsMetricsAndRsiOfTheSamePublication() throws Exception {
        financials(mvc,FIRST,RUN).andExpect(status().isOk())
            .andExpect(jsonPath("$.run.id").value(RUN)).andExpect(jsonPath("$.run.asOfDate").value("2026-07-31"))
            .andExpect(jsonPath("$.run.mappingVersion").value("sec-us-gaap-v2"))
            .andExpect(jsonPath("$.instrument.symbol").value("OLD1")).andExpect(jsonPath("$.instrument.sector").value("Technology"))
            .andExpect(jsonPath("$.instrument.financialPeriodEnd").value("2026-06-30"))
            .andExpect(jsonPath("$.score.eligible").value(true)).andExpect(jsonPath("$.score.rank").value(1))
            .andExpect(jsonPath("$.score.scoreStatus").value("PARTIAL"))
            .andExpect(jsonPath("$.inputs[?(@.code=='REVENUE_TTM')].valueKind").value(org.hamcrest.Matchers.contains("DERIVED_TTM")))
            .andExpect(jsonPath("$.inputs[?(@.code=='REVENUE_TTM')].periodType").value(org.hamcrest.Matchers.contains("TTM")))
            .andExpect(jsonPath("$.inputs[?(@.code=='REVENUE_TTM')].category").value(org.hamcrest.Matchers.contains("INCOME_STATEMENT")))
            .andExpect(jsonPath("$.inputs[?(@.code=='REVENUE_TTM')].filedDate").value(org.hamcrest.Matchers.contains("2026-07-15")))
            .andExpect(jsonPath("$.inputs[?(@.code=='CASH')].valueKind").value(org.hamcrest.Matchers.contains("REPORTED")))
            .andExpect(jsonPath("$.inputs[?(@.code=='SHARES_OUTSTANDING')].unit").value(org.hamcrest.Matchers.contains("shares")))
            .andExpect(jsonPath("$.inputs[?(@.code=='RAW_CLOSE')].valueKind").value(org.hamcrest.Matchers.contains("MARKET")))
            .andExpect(jsonPath("$.inputs[?(@.code=='RAW_CLOSE')].availabilityBasis").value(org.hamcrest.Matchers.contains("PHASE6_CUTOFF_UPPER_BOUND")))
            .andExpect(jsonPath("$.inputs[?(@.code=='COMMON_EQUITY')].status").value(org.hamcrest.Matchers.contains("UNAVAILABLE")))
            .andExpect(jsonPath("$.inputs[?(@.code=='COMMON_EQUITY')].reason").value(org.hamcrest.Matchers.contains("NOT_REPORTED")))
            .andExpect(jsonPath("$.inputs[?(@.code=='TOTAL_ASSETS_PRIOR')].status").value(org.hamcrest.Matchers.contains("NOT_USED_BY_MODEL")))
            .andExpect(jsonPath("$.inputs[?(@.code=='TOTAL_ASSETS_PRIOR')].reason").value(org.hamcrest.Matchers.contains("INSUFFICIENT_HISTORY")))
            .andExpect(jsonPath("$.derived[?(@.code=='market_cap')].value").value(org.hamcrest.Matchers.contains(456.4)))
            .andExpect(jsonPath("$.derived[?(@.code=='market_cap')].unit").value(org.hamcrest.Matchers.contains("USD")))
            .andExpect(jsonPath("$.derived[?(@.code=='market_cap')].formula").value(org.hamcrest.Matchers.contains("RAW_CLOSE * instrument SHARES_OUTSTANDING")))
            .andExpect(jsonPath("$.derived[?(@.code=='ev')].status").value(org.hamcrest.Matchers.contains("UNAVAILABLE")))
            .andExpect(jsonPath("$.derived[?(@.code=='ev')].reason").value(org.hamcrest.Matchers.contains("NOT_REPORTED")))
            .andExpect(jsonPath("$.derived[?(@.code=='metric1' || @.code=='REVENUE_TTM')]").isEmpty())
            .andExpect(jsonPath("$.metrics.length()").value(7))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("12345678901234567890.123456789")))
            .andExpect(jsonPath("$.technical.rsi.value").value(57.915))
            .andExpect(jsonPath("$.technical.rsi.version").value("RSI_14_WILDER_V1"))
            .andExpect(jsonPath("$.technical.rsi.sessionsUsed").value(20))
            .andExpect(jsonPath("$.technical.rsi.firstSession").value("2026-07-06"))
            .andExpect(jsonPath("$.technical.rsi.lastSession").value("2026-07-31"))
            .andExpect(jsonPath("$.technical.rsi.adjustedDataset").value("10000000-0000-0000-0000-000000000002"))
            .andExpect(jsonPath("$.technical.rsi.partOfScore").value(false))
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.value").value(44))
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.ebitda").value(344))
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.concept").value("us-gaap:DepreciationDepletionAndAmortization"))
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.version").value("EBITDA_FROM_SOURCE_V1"))
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.periodEnd").value("2026-06-30"))
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.partOfScore").value(false))
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.components.length()").value(3))
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.components[?(@.sign==-1)].value").value(org.hamcrest.Matchers.contains(18)))
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.components[?(@.accession=='0000000001-26-000001')].sourceArtifactId")
                .value(org.hamcrest.Matchers.contains("10000000-0000-0000-0000-000000000010")));
    }
    @Test void depreciationIgnoresResponsesRetrievedAfterTheCutoff() throws Exception {
        jdbc.sql("UPDATE operations.source_artifacts SET retrieved_at='2026-09-01' WHERE source_artifact_id='10000000-0000-0000-0000-000000000010'").update();
        financials(mvc,FIRST,RUN).andExpect(status().isOk())
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.value").doesNotExist())
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.reason").value("SOURCE_ARTIFACT_NOT_RETAINED"));
    }
    @Test void financialsOfExcludedInstrumentsAndUnrecordedPriceDatasetsAreExplicitGaps() throws Exception {
        financials(mvc,EXCLUDED,RUN).andExpect(status().isOk())
            .andExpect(jsonPath("$.score.eligible").value(false)).andExpect(jsonPath("$.score.rank").doesNotExist())
            .andExpect(jsonPath("$.score.exclusions[0].reasonCode").value("MODEL_NOT_SUPPORTED"))
            .andExpect(jsonPath("$.inputs").isEmpty())
            .andExpect(jsonPath("$.technical.rsi.value").doesNotExist())
            .andExpect(jsonPath("$.technical.rsi.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.technical.rsi.reason").value("MISSING_SCORE_DATE_PRICE"))
            .andExpect(jsonPath("$.supplemental.depreciationAmortization.reason").value("MISSING_INPUT:OPERATING_INCOME_TTM"));
        financials(mvc,FIRST,"50000000-0000-0000-0000-000000000001").andExpect(status().isOk())
            .andExpect(jsonPath("$.technical.rsi.reason").value("PRICE_DATASET_NOT_RECORDED"));
    }
    @Test void defaultSummaryShowsUserIndicatorsWithoutNullNoise() throws Exception {
        var result=mvc.perform(get("/screener/instruments/"+FIRST+"/financials").header("Authorization",READER_TOKEN).param("runId",RUN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.company.symbol").value("OLD1")).andExpect(jsonPath("$.company.runId").value(RUN))
            .andExpect(jsonPath("$.definitionsVersion").value("DISPLAY_METRICS_V2"))
            .andExpect(jsonPath("$.valuation.marketCap.value").value(456.4))
            .andExpect(jsonPath("$.valuation.priceToSales.value").value(0.46))
            .andExpect(jsonPath("$.valuation.priceToSales.unit").value("MULTIPLE"))
            .andExpect(jsonPath("$.valuation.priceToSales.period").value("TTM to 2026-06-30"))
            .andExpect(jsonPath("$.valuation.priceToEarnings.reason").value("MISSING_INPUT:NET_INCOME_TTM"))
            .andExpect(jsonPath("$.valuation.enterpriseValue.reason").value("ev:NOT_REPORTED"))
            .andExpect(jsonPath("$.financials.revenue.value").value(1000))
            .andExpect(jsonPath("$.financials.revenue.period").value("TTM to 2026-06-30"))
            .andExpect(jsonPath("$.financials.ebitda.value").value(344))
            .andExpect(jsonPath("$.financials.ebitda.period").value("TTM to 2026-06-30"))
            .andExpect(jsonPath("$.financials.depreciationAmortization.value").value(44))
            .andExpect(jsonPath("$.profitability.operatingMargin.value").value(30))
            .andExpect(jsonPath("$.financials.totalAssets.reason").value("MISSING_INPUT:TOTAL_ASSETS"))
            .andExpect(jsonPath("$.market.rsi14.value").value(57.915))
            .andExpect(jsonPath("$.market.high52w.reason").value("INSUFFICIENT_PRICE_HISTORY"))
            .andExpect(jsonPath("$.score.rank").value(1)).andExpect(jsonPath("$.score.rankedInstruments").value(2))
            .andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain("null").doesNotContain("sourceIds").doesNotContain("cohortMean");
        mvc.perform(get("/screener/instruments/"+FIRST+"/financials").header("Authorization",READER_TOKEN)
                .param("runId",RUN).param("view","bad")).andExpect(status().isBadRequest());
    }
    @Test void financialsRequireAPublishedRunContainingTheInstrument() throws Exception {
        mvc.perform(get("/screener/instruments/"+FIRST+"/financials").param("runId",RUN)).andExpect(status().isUnauthorized());
        financials(mvc,FIRST,null).andExpect(status().isBadRequest());
        financials(mvc,"not-a-uuid",RUN).andExpect(status().isBadRequest());
        financials(mvc,FIRST,"bad").andExpect(status().isBadRequest());
        for(String run:List.of(UUID.randomUUID().toString(),"50000000-0000-0000-0000-000000000003","50000000-0000-0000-0000-000000000004"))
            financials(mvc,FIRST,run).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Publication not found"));
        financials(mvc,UUID.randomUUID().toString(),RUN).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Instrument is not part of the publication"));
    }
    @Test void searchReadsCompaniesAndTheLatestPublicationFromPostgres() throws Exception {
        var results=search.search("Fixture",0,10);
        assertThat(results.totalElements()).isEqualTo(3);
        assertThat(results.content()).extracting(CompanySearchItem::instrumentId).doesNotHaveDuplicates();
        assertThat(results.content()).filteredOn(d->d.symbol().equals("NEW1")).singleElement().satisfies(d->{
            assertThat(d.instrumentId()).isEqualTo("30000000-0000-0000-0000-000000000001");
            assertThat(d.score().get("symbolAtScore")).isEqualTo("OLD1");
            assertThat(d.score().get("runId")).isEqualTo(RUN);
        });
        mvc.perform(get("/screener/search").header("Authorization",READER_TOKEN).param("q","Fixture"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.content[0].schemaVersion").value(1));
        mvc.perform(get("/screener/search/status").header("Authorization",READER_TOKEN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("LIVE")).andExpect(jsonPath("$.backend").value("POSTGRESQL"));
    }
    @Test void symbolOutranksNameAndEveryTokenMustMatch() throws Exception {
        var symbol=search.search("new2",0,10);
        assertThat(symbol.content()).first().satisfies(d->{
            assertThat(d.symbol()).isEqualTo("NEW2");
            assertThat(d.relevance()).isEqualTo(10);
        });
        assertThat(search.search("NEW",0,10).totalElements()).isEqualTo(3);
        assertThat(search.search("fixture company 2",0,10).content()).singleElement()
            .extracting(CompanySearchItem::instrumentId).isEqualTo("30000000-0000-0000-0000-000000000002");
        assertThat(search.search("Fixture zzz",0,10).totalElements()).isZero();
        var paged=search.search("Fixture",1,2);
        assertThat(paged.totalElements()).isEqualTo(3);
        assertThat(paged.totalPages()).isEqualTo(2);
        assertThat(paged.content()).hasSize(1);
    }
    @Test void likeWildcardsAreLiteralAndCanonicalChangesAreImmediate() throws Exception {
        assertThat(search.search("%",0,10).totalElements()).isZero();
        assertThat(search.search("_",0,10).totalElements()).isZero();
        jdbc.sql("UPDATE reference.issuers SET legal_name='Renamed canonical company' WHERE issuer_id='20000000-0000-0000-0000-000000000001'").update();
        assertThat(search.search("Renamed",0,10).content()).singleElement()
            .extracting(CompanySearchItem::instrumentId).isEqualTo("30000000-0000-0000-0000-000000000001");
    }
}
