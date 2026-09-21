package com.quantplatform.screener;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.sql.*;
import com.quantplatform.screener.search.*;
import com.quantplatform.screener.config.ScreenerProperties;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
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
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties={"screener.search-rebuild.enabled=false","spring.sql.init.mode=never"})
@AutoConfigureMockMvc
@Transactional
@Sql("/publication-fixture.sql")
class ScreenerApplicationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(DockerImageName
        .parse("timescale/timescaledb:2.29.1-pg18").asCompatibleSubstituteFor("postgres"));
    @Container static final ElasticsearchContainer ES=new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.11.0")
        .withEnv("xpack.security.enabled","false").withEnv("ES_JAVA_OPTS","-Xms512m -Xmx512m");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE");
        }
        Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())
            .defaultSchema("operations").schemas("operations").locations("classpath:db/migration").load().migrate();
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
        r.add("screener.elasticsearch.url",ES::getHttpHostAddress);
        r.add("screener.elasticsearch.company-index",()->"screener-test");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ElasticsearchClient client;
    @Autowired SearchRebuildService rebuild;
    @Autowired CompanySearchService search;
    static final String RUN="50000000-0000-0000-0000-000000000002";

    @Test void latestPublicationRejectsPendingAndFailedRunsAndKeepsOriginalTieRanks() throws Exception {
        mvc.perform(get("/screener/rankings").param("asOf","2026-08-31").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.run.id").value(RUN))
            .andExpect(jsonPath("$.run.complete").value(true)).andExpect(jsonPath("$.run.expectedCount").value(3))
            .andExpect(jsonPath("$.run.profileCoverage.GENERAL.scored").value(2))
            .andExpect(jsonPath("$.run.classificationVersionId").value("60000000-0000-0000-0000-000000000001"))
            .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.content[0].symbol").value("OLD1"))
            .andExpect(jsonPath("$.content[0].rank").value(1)).andExpect(jsonPath("$.content[0].factors.length()").value(7))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("12345678901234567890.123456789")));
        mvc.perform(get("/screener/rankings").param("asOf","2026-08-31").param("size","1").param("page","1").param("runId",RUN))
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
        mvc.perform(get("/screener/rankings")).andExpect(status().isOk()).andExpect(jsonPath("$.run.id").value(newer));
        mvc.perform(get("/screener/rankings").param("runId",RUN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.run.id").value(RUN));
    }
    @Test void filtersMembershipProfileWarningsAndSectorWithoutReranking() throws Exception {
        mvc.perform(get("/screener/rankings").param("asOf","2026-08-01").param("universe","NASDAQ100")
            .param("profile","GENERAL").param("sector","Technology").param("peerGroup","Technology").param("warning","NO_DISPERSION"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].rank").value(2));
        mvc.perform(get("/screener/rankings").param("universe","SP500").param("sector","Technology' OR true --"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void sortsFamiliesAndContributionsAndInspectsExclusions() throws Exception {
        for(String sort:List.of("VALUE","CONTRIBUTION")) {
            mvc.perform(get("/screener/rankings").param("sort",sort).param("metric","metric1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].symbol").value("OLD2"));
        }
        for(String sort:List.of("QUALITY","MOMENTUM")) {
            mvc.perform(get("/screener/rankings").param("sort",sort))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].symbol").value("OLD1"));
        }
        mvc.perform(get("/screener/rankings").param("direction","ASC"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].symbol").value("OLD2"));
        mvc.perform(get("/screener/rankings").param("eligibility","EXCLUDED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].eligible").value(false))
            .andExpect(jsonPath("$.content[0].rank").doesNotExist())
            .andExpect(jsonPath("$.content[0].exclusions[0].reason_code").value("MODEL_NOT_SUPPORTED"));
        mvc.perform(get("/screener/rankings").param("eligibility","ALL"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/screener/rankings").param("eligibility","ALL").param("instrumentId","30000000-0000-0000-0000-000000000003"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].symbol").value("OLD3"));
    }
    @Test void datesModelSelectionAndFreshnessAreExplicit() throws Exception {
        mvc.perform(get("/screener/rankings").param("asOf","2026-07-15T00:00:00Z"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.run.asOfDate").value("2026-06-30"))
            .andExpect(jsonPath("$.freshness").value("CURRENT"));
        mvc.perform(get("/screener/rankings").param("asOf","2026-10-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.freshness").value("STALE"));
        mvc.perform(get("/screener/rankings").param("asOf","2026-05-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.freshness").value("UNAVAILABLE"));
        mvc.perform(get("/screener/rankings").param("modelVersion",UUID.randomUUID().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        String model=jdbc.sql("SELECT model_version_id::text FROM research.model_versions").query(String.class).single();
        mvc.perform(get("/screener/rankings").param("modelVersion",model))
            .andExpect(status().isOk()).andExpect(jsonPath("$.run.modelVersionId").value(model));
    }
    @Test void validatesQueriesAndNeverExposesAnUnpublishedPinnedRun() throws Exception {
        for(var pair:List.of(new String[]{"size","201"},new String[]{"asOf","bad"},new String[]{"universe","BAD"},
                new String[]{"sort","bad"},new String[]{"sort","CONTRIBUTION"},new String[]{"page","-1"}))
            mvc.perform(get("/screener/rankings").param(pair[0],pair[1])).andExpect(status().isBadRequest());
        mvc.perform(get("/screener/search").param("q","   ")).andExpect(status().isBadRequest());
        mvc.perform(get("/screener/search").param("q","test").param("page","500").param("size","100"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/screener/rankings").param("runId","50000000-0000-0000-0000-000000000003"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.freshness").value("UNAVAILABLE"));
    }
    @Test void fullRebuildIsIdempotentAndRestoresDeletedSearchWithStableIdentity() throws Exception {
        String first=rebuild.rebuild(true);
        assertThat(rebuild.rebuild(false)).isEqualTo("UNCHANGED");
        var results=search.search("Fixture",0,10);
        assertThat(results.totalElements()).isEqualTo(3);
        assertThat(client.count(r->r.index("screener-test").query(q->q.term(t->t.field("score.runId").value(RUN)))).count()).isEqualTo(3);
        assertThat(results.content()).extracting(CompanySearchItem::instrumentId).doesNotHaveDuplicates();
        assertThat(results.content()).filteredOn(d->d.symbol().equals("NEW1")).singleElement().satisfies(d->{
            assertThat(d.instrumentId()).isEqualTo("30000000-0000-0000-0000-000000000001");
            assertThat(d.score().get("symbolAtScore")).isEqualTo("OLD1");
            assertThat(d.score().get("runId")).isEqualTo(RUN);
        });
        mvc.perform(get("/screener/search/status")).andExpect(status().isOk())
            .andExpect(jsonPath("$.schema_version").value(1)).andExpect(jsonPath("$.document_count").value(3));
        client.indices().delete(r->r.index(first));
        mvc.perform(get("/screener/search").param("q","Fixture")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/screener/rankings")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        String second=rebuild.rebuild(false);
        assertThat(second).isNotEqualTo(first);
        assertThat(search.search("Fixture",0,10).totalElements()).isEqualTo(3);
        jdbc.sql("UPDATE reference.issuers SET legal_name='Renamed canonical company' WHERE issuer_id='20000000-0000-0000-0000-000000000001'").update();
        rebuild.rebuild(false);
        assertThat(search.search("Renamed",0,10).content()).singleElement()
            .extracting(CompanySearchItem::instrumentId).isEqualTo("30000000-0000-0000-0000-000000000001");
    }
    @Test void partialBulkFailureKeepsPreviousAliasAndCheckpoint() throws Exception {
        String first=rebuild.rebuild(true);
        // Inject a write failure after a fresh generation was created in the real cluster.
        var spy=org.mockito.Mockito.spy(client);
        org.mockito.Mockito.doThrow(new java.io.IOException("injected bulk failure")).when(spy)
            .bulk(org.mockito.ArgumentMatchers.<java.util.function.Function<co.elastic.clients.elasticsearch.core.BulkRequest.Builder,
                co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch.core.BulkRequest>>>any());
        var broken=new SearchRebuildService(jdbc,spy,new ScreenerProperties(new ScreenerProperties.Elasticsearch("unused","screener-test")),Clock.systemUTC());
        assertThatThrownBy(()->broken.rebuild(true)).isInstanceOf(java.io.IOException.class);
        assertThat(client.indices().getAlias(r->r.name("screener-test")).result()).containsOnlyKeys(first);
        assertThat(rebuild.status().get("index_name")).isEqualTo(first);
        org.mockito.Mockito.doReturn(co.elastic.clients.elasticsearch.core.BulkResponse.of(b->b.errors(true).took(1).items(List.of())))
            .when(spy).bulk(org.mockito.ArgumentMatchers.<java.util.function.Function<co.elastic.clients.elasticsearch.core.BulkRequest.Builder,
                co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch.core.BulkRequest>>>any());
        assertThatThrownBy(()->broken.rebuild(true)).isInstanceOf(java.io.IOException.class).hasMessageContaining("bulk failed");
        assertThat(client.indices().getAlias(r->r.name("screener-test")).result()).containsOnlyKeys(first);
        assertThat(search.search("Fixture",0,10).totalElements()).isEqualTo(3);
    }
    @Test void checkpointLossAfterAliasSwapIsRecovered() throws Exception {
        String first=rebuild.rebuild(true);
        jdbc.sql("DELETE FROM operations.search_rebuild_checkpoints").update();
        assertThat(rebuild.rebuild(false)).isNotEqualTo(first);
        assertThat(rebuild.status().get("document_count")).isEqualTo(3);
        assertThat(search.search("Fixture",0,10).totalElements()).isEqualTo(3);
    }
}
