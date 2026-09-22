package com.quantplatform.migrations;
import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
@Testcontainers
class DatabaseRoleIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.29.1-pg18").asCompatibleSubstituteFor("postgres"));
    @BeforeAll static void migrate() throws Exception {
        try(var c=connection();var s=c.createStatement()){s.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE");}
        Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).defaultSchema("operations").schemas("operations")
            .locations("classpath:db/migration").load().migrate();
    }
    static Connection connection() throws SQLException {return DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());}
    void allowed(String role,String sql) throws Exception {
        try(var c=connection();var s=c.createStatement()){s.execute("SET ROLE "+role);s.execute(sql);}
    }
    void denied(String role,String sql) throws Exception {
        try(var c=connection();var s=c.createStatement()) {
            s.execute("SET ROLE "+role);
            assertThatThrownBy(()->s.execute(sql)).isInstanceOf(SQLException.class)
                .satisfies(e->assertThat(((SQLException)e).getSQLState()).isEqualTo("42501"));
        }
    }
    @Test void freshDatabaseCanMigrateUsingOnlyTheRestrictedMigrator() throws Exception {
        try(var c=connection();var statement=c.createStatement()) {
            statement.execute("CREATE DATABASE fresh_role_bootstrap");
            statement.execute("ALTER ROLE quant_migrator LOGIN PASSWORD 'isolated-test-migration-password'");
        }
        String url=DB.getJdbcUrl().replace("/"+DB.getDatabaseName(),"/fresh_role_bootstrap");
        try(var c=DriverManager.getConnection(url,DB.getUsername(),DB.getPassword());var statement=c.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE");
            String bootstrap=java.nio.file.Files.readString(java.nio.file.Path.of("../infrastructure/bootstrap-roles.sql"));
            bootstrap=bootstrap.replace("SELECT format('GRANT CONNECT,CREATE ON DATABASE %I TO quant_migrator',current_database()) \\gexec",
                "DO $$ BEGIN EXECUTE format('GRANT CONNECT,CREATE ON DATABASE %I TO quant_migrator',current_database()); END $$;")
                .replace("SELECT format('ALTER DATABASE %I OWNER TO quant_migrator',current_database()) \\gexec",
                "DO $$ BEGIN EXECUTE format('ALTER DATABASE %I OWNER TO quant_migrator',current_database()); END $$;");
            statement.execute(bootstrap);
        }
        var flyway=Flyway.configure().dataSource(url,"quant_migrator","isolated-test-migration-password")
            .defaultSchema("operations").schemas("operations").locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(18);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    @Test void everyApplicationRoleIsNonAdministrativeAndCannotAssumeTheMigrator() throws Exception {
        for(String role:List.of("quant_auth","quant_portfolio","quant_ingestion","quant_scoring","quant_screener","quant_search","quant_research")) {
            try(var c=connection();var s=c.createStatement();var rows=s.executeQuery("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls OR pg_has_role('"+role+"','quant_migrator','MEMBER') FROM pg_roles WHERE rolname='"+role+"'")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getBoolean(1)).isFalse();
            }
            denied(role,"CREATE TABLE public.forbidden_table(id int)");
            denied(role,"TRUNCATE public.users");
            denied(role,"SELECT * FROM operations.flyway_schema_history");
        }
    }
    @Test void authAndPortfolioCanOnlyModifyTheirOwnTables() throws Exception {
        allowed("quant_auth","INSERT INTO public.users(id,username,password_hash) VALUES('70000000-0000-0000-0000-000000000001','role-user','hash')");
        allowed("quant_portfolio","INSERT INTO public.portfolios(user_id,ticker,quantity,entry_price,purchased_at) VALUES('70000000-0000-0000-0000-000000000001','AAPL',1,10,now())");
        allowed("quant_auth","UPDATE public.users SET username=username WHERE false");
        allowed("quant_portfolio","DELETE FROM public.portfolios WHERE false");
        denied("quant_auth","SELECT * FROM public.portfolios");denied("quant_portfolio","SELECT password_hash FROM public.users");
        denied("quant_auth","SELECT * FROM research.scoring_runs");denied("quant_portfolio","SELECT * FROM reference.issuers");
    }
    @Test void ingestionAndScoringHaveDistinctWritesAndCanReconcile() throws Exception {
        allowed("quant_ingestion","INSERT INTO reference.issuers(legal_name) VALUES('Role fixture')");
        allowed("quant_ingestion","UPDATE operations.outbox_events SET status=status WHERE false");
        allowed("quant_scoring","UPDATE operations.kafka_inbox SET delivery_count=delivery_count WHERE false");
        allowed("quant_scoring","SELECT operations.reconcile_ingestion_coverage('role-test')");
        allowed("quant_ingestion","SELECT operations.reconcile_ingestion_coverage('role-test')");
        denied("quant_ingestion","UPDATE operations.kafka_inbox SET delivery_count=delivery_count WHERE false");
        denied("quant_ingestion","UPDATE research.stock_scores SET warnings=warnings WHERE false");
        denied("quant_scoring","UPDATE operations.outbox_events SET status=status WHERE false");
        denied("quant_scoring","SELECT password_hash FROM public.users");
    }
    @Test void readersCannotWriteAndOnlySearchWorkerCanCheckpoint() throws Exception {
        for(String role:List.of("quant_screener","quant_search","quant_research")) {
            allowed(role,"SELECT * FROM research.stock_scores");
            denied(role,"UPDATE research.stock_scores SET warnings=warnings WHERE false");
            denied(role,"SELECT password_hash FROM public.users");
            denied(role,"SELECT operations.reconcile_ingestion_coverage('forbidden')");
        }
        denied("quant_screener","UPDATE operations.search_rebuild_checkpoints SET schema_version=1 WHERE false");
        allowed("quant_search","INSERT INTO operations.search_rebuild_checkpoints VALUES('role-test',1,'index',repeat('a',64),0,now(),now())");
        allowed("quant_research","SELECT * FROM fundamentals.fundamental_facts");
    }
    @Test void migratorOwnsDdlAndNewFunctionsAreNotPubliclyExecutable() throws Exception {
        allowed("quant_migrator","CREATE TABLE research.role_probe(id integer)");
        allowed("quant_migrator","DROP TABLE research.role_probe");
        allowed("quant_migrator","CREATE FUNCTION operations.role_probe() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
        denied("quant_screener","SELECT operations.role_probe()");
        allowed("quant_migrator","DROP FUNCTION operations.role_probe()");
        denied("quant_migrator","CREATE ROLE forbidden_role");
    }
}
