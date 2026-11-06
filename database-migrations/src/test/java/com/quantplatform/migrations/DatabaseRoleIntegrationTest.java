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
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(30);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    @Test void administratorOwnedRepeatablesCanBeRepairedForRestrictedMigrations() throws Exception {
        try(var c=connection();var statement=c.createStatement()) {
            statement.execute("CREATE DATABASE repeatable_ownership_upgrade");
        }
        String url=DB.getJdbcUrl().replace("/"+DB.getDatabaseName(),"/repeatable_ownership_upgrade");
        try(var c=DriverManager.getConnection(url,DB.getUsername(),DB.getPassword());var statement=c.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE");
        }
        Flyway.configure().dataSource(url,DB.getUsername(),DB.getPassword())
            .defaultSchema("operations").schemas("operations").locations("classpath:db/migration").load().migrate();
        String grants=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/R__security_grants.sql"));
        String repair=java.nio.file.Files.readString(java.nio.file.Path.of("../infrastructure/repair-migration-ownership.sql"));
        try(var c=DriverManager.getConnection(url,DB.getUsername(),DB.getPassword());var statement=c.createStatement()) {
            statement.execute("SET ROLE quant_migrator");
            assertThatThrownBy(()->statement.execute(grants)).isInstanceOf(SQLException.class)
                .satisfies(e->assertThat(((SQLException)e).getSQLState()).isEqualTo("42501"));
            statement.execute("RESET ROLE");
            statement.execute(repair);
            statement.execute(repair); // Safe to repeat after an uncertain operator outcome.
            try(var rows=statement.executeQuery("""
                SELECT c.relowner='quant_migrator'::regrole AND p.proowner='quant_migrator'::regrole
                FROM pg_class c CROSS JOIN pg_proc p
                WHERE c.oid='operations.input_reviews'::regclass
                    AND p.oid='operations.validate_input_review()'::regprocedure
                """)) { rows.next();assertThat(rows.getBoolean(1)).isTrue(); }
            try(var rows=statement.executeQuery("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE c.relkind='v' AND n.nspname IN ('market_data','fundamentals')
                  AND c.relowner='quant_migrator'::regrole
                """)) { rows.next();assertThat(rows.getInt(1)).isEqualTo(4); }
            try(var rows=statement.executeQuery("""
                SELECT count(*) FROM pg_proc p WHERE p.proowner='quant_migrator'::regrole
                  AND p.oid IN ('market_data.daily_bars_as_of(uuid,text,date,timestamptz)'::regprocedure,
                    'fundamentals.facts_as_of(uuid,timestamptz,text)'::regprocedure,
                    'fundamentals.regulatory_facts_as_of(uuid,timestamptz)'::regprocedure,
                    'operations.reconcile_ingestion_coverage(text)'::regprocedure)
                """)) { rows.next();assertThat(rows.getInt(1)).isEqualTo(4); }
            statement.execute("SET ROLE quant_migrator");
            statement.execute(grants);
            statement.execute("SELECT operations.reconcile_ingestion_coverage('ownership-repair')");
        }
        var restricted=Flyway.configure().dataSource(url,DB.getUsername(),DB.getPassword())
            .initSql("SET ROLE quant_migrator").defaultSchema("operations").schemas("operations")
            .locations("classpath:db/migration").load();
        restricted.validate();assertThat(restricted.migrate().migrationsExecuted).isZero();
    }
    static final List<String> CURRENT_ROLES=List.of("quant_web","quant_ingestion","quant_scoring","quant_research");
    @Test void everyApplicationRoleIsNonAdministrativeAndCannotAssumeTheMigrator() throws Exception {
        for(String role:CURRENT_ROLES) {
            try(var c=connection();var s=c.createStatement();var rows=s.executeQuery("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls OR pg_has_role('"+role+"','quant_migrator','MEMBER') FROM pg_roles WHERE rolname='"+role+"'")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getBoolean(1)).isFalse();
            }
            denied(role,"CREATE TABLE public.forbidden_table(id int)");
            denied(role,"TRUNCATE public.users");
            denied(role,"SELECT * FROM operations.flyway_schema_history");
        }
    }
    @Test void webBackendOwnsAccountsAndHoldingsAndOnlyReadsThePublication() throws Exception {
        allowed("quant_web","INSERT INTO public.users(id,username,password_hash) VALUES('70000000-0000-0000-0000-000000000001','role-user','hash')");
        allowed("quant_web","INSERT INTO public.portfolios(user_id,ticker,quantity,entry_price,purchased_at) VALUES('70000000-0000-0000-0000-000000000001','AAPL',1,10,now())");
        allowed("quant_web","UPDATE public.users SET username=username WHERE false");
        allowed("quant_web","DELETE FROM public.portfolios WHERE false");
        denied("quant_web","DELETE FROM public.users WHERE false");
        allowed("quant_web","SELECT * FROM research.stock_scores");
        allowed("quant_web","SELECT * FROM reference.issuers WHERE false");
        denied("quant_web","UPDATE research.stock_scores SET warnings=warnings WHERE false");
        // P2b: RSI reads the adjusted daily bars; EBITDA_FROM_SOURCE_V1 reads facts, filings and retained responses.
        allowed("quant_web","SELECT * FROM market_data.daily_bar_observations WHERE false");
        denied("quant_web","UPDATE market_data.daily_bar_observations SET close=close WHERE false");
        denied("quant_web","SELECT * FROM market_data.corporate_actions WHERE false");
        allowed("quant_web","SELECT * FROM fundamentals.fundamental_facts WHERE false");
        allowed("quant_web","SELECT * FROM fundamentals.filings WHERE false");
        allowed("quant_web","SELECT * FROM operations.source_artifacts WHERE false");
        denied("quant_web","UPDATE fundamentals.fundamental_facts SET priority=priority WHERE false");
        denied("quant_web","UPDATE operations.source_artifacts SET media_type=media_type WHERE false");
        denied("quant_web","SELECT * FROM fundamentals.statements WHERE false");
        denied("quant_web","SELECT * FROM operations.outbox_events WHERE false");
        denied("quant_web","SELECT * FROM public.market_bars WHERE false");
        denied("quant_web","SELECT operations.reconcile_ingestion_coverage('forbidden')");
    }
    @Test void collectorOwnsStagingAndCanonicalWritesInOneIdentity() throws Exception {
        // P2s stage B: the canonical consumer runs inside the collector.
        allowed("quant_ingestion","INSERT INTO reference.issuers(legal_name) VALUES('Role fixture')");
        allowed("quant_ingestion","UPDATE operations.outbox_events SET status=status WHERE false");
        allowed("quant_ingestion","SELECT event_id FROM operations.outbox_events WHERE false FOR UPDATE SKIP LOCKED");
        allowed("quant_ingestion","UPDATE operations.kafka_inbox SET delivery_count=delivery_count WHERE false");
        allowed("quant_ingestion","UPDATE operations.dead_letter_records SET reason=reason WHERE false");
        allowed("quant_ingestion","UPDATE operations.dead_letter_replays SET reason=reason WHERE false");
        allowed("quant_ingestion","UPDATE market_data.daily_bar_observations SET close=close WHERE false");
        allowed("quant_ingestion","UPDATE fundamentals.fundamental_facts SET priority=priority WHERE false");
        allowed("quant_ingestion","UPDATE public.market_bars SET provider=provider WHERE false");
        allowed("quant_ingestion","SELECT operations.reconcile_ingestion_coverage('role-test')");
        denied("quant_ingestion","DELETE FROM operations.outbox_events WHERE false");
        denied("quant_ingestion","DELETE FROM market_data.observations WHERE false");
        denied("quant_ingestion","UPDATE research.stock_scores SET warnings=warnings WHERE false");
        denied("quant_ingestion","SELECT password_hash FROM public.users");
        denied("quant_ingestion","SELECT * FROM public.portfolios");
    }
    @Test void publisherWritesOnlyResearchAndAuditedReviews() throws Exception {
        allowed("quant_scoring","UPDATE research.scoring_runs SET attempts=attempts WHERE false");
        allowed("quant_scoring","SELECT * FROM operations.outbox_events WHERE false");
        allowed("quant_scoring","SELECT * FROM fundamentals.fundamental_facts WHERE false");
        allowed("quant_scoring","SELECT * FROM operations.input_reviews WHERE false");
        // The consumer writes it held before stage B were removed by V026.
        denied("quant_scoring","UPDATE operations.kafka_inbox SET delivery_count=delivery_count WHERE false");
        denied("quant_scoring","UPDATE operations.outbox_events SET status=status WHERE false");
        denied("quant_scoring","UPDATE operations.ingestion_runs SET status=status WHERE false");
        denied("quant_scoring","UPDATE market_data.daily_bar_observations SET close=close WHERE false");
        denied("quant_scoring","UPDATE fundamentals.fundamental_facts SET priority=priority WHERE false");
        denied("quant_scoring","UPDATE reference.instrument_symbols SET symbol=symbol WHERE false");
        denied("quant_scoring","UPDATE public.market_bars SET provider=provider WHERE false");
        denied("quant_scoring","SELECT operations.reconcile_ingestion_coverage('forbidden')");
        denied("quant_scoring","SELECT password_hash FROM public.users");
    }
    @Test void researchReaderCannotWriteAndRetiredRolesHaveNoAccess() throws Exception {
        allowed("quant_research","SELECT * FROM research.stock_scores");
        allowed("quant_research","SELECT * FROM fundamentals.fundamental_facts");
        denied("quant_research","UPDATE research.stock_scores SET warnings=warnings WHERE false");
        denied("quant_research","SELECT password_hash FROM public.users");
        denied("quant_research","SELECT operations.reconcile_ingestion_coverage('forbidden')");
        // P2s: the Elasticsearch indexer (V025) and the merged web services (V026) keep no privileges.
        for(String retired:List.of("quant_search","quant_auth","quant_portfolio","quant_screener")) {
            denied(retired,"SELECT * FROM research.stock_scores");
            denied(retired,"SELECT * FROM reference.issuers");
            denied(retired,"SELECT * FROM public.users");
            denied(retired,"SELECT * FROM public.portfolios");
            denied(retired,"SELECT * FROM market_data.daily_bar_observations WHERE false");
            try(var c=connection();var s=c.createStatement();var rows=s.executeQuery(
                    "SELECT count(*) FROM information_schema.role_table_grants WHERE grantee='"+retired+"'")) {
                rows.next();assertThat(rows.getInt(1)).as(retired+" table grants").isZero();
            }
        }
    }
    @Test void migratorOwnsDdlAndNewFunctionsAreNotPubliclyExecutable() throws Exception {
        allowed("quant_migrator","CREATE TABLE research.role_probe(id integer)");
        allowed("quant_migrator","DROP TABLE research.role_probe");
        allowed("quant_migrator","CREATE FUNCTION operations.role_probe() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
        denied("quant_web","SELECT operations.role_probe()");
        allowed("quant_migrator","DROP FUNCTION operations.role_probe()");
        denied("quant_migrator","CREATE ROLE forbidden_role");
    }
}
