package com.quantplatform.migrations;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DatabaseMigrationIntegrationTest {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.29.1-pg18")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> TIMESCALE = new PostgreSQLContainer<>(TIMESCALE_IMAGE)
            .withDatabaseName("postgres")
            .withUsername("postgres")
            .withPassword("postgres");

    @Test
    void freshMigrationIsValidIdempotentAndCreatesHypertables() throws SQLException {
        var database = createDatabase();
        var flyway = flyway(database, null);

        var firstRun = flyway.migrate();

        assertThat(firstRun.migrationsExecuted).isEqualTo(16);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (var connection = DriverManager.getConnection(jdbcUrl(database), "postgres", "postgres")) {
            assertThat(queryInt(connection, """
                    SELECT COUNT(*) FROM timescaledb_information.hypertables
                    WHERE hypertable_schema='market_data' AND hypertable_name='daily_bar_observations'
                    """)).isEqualTo(1);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*) FROM timescaledb_information.jobs
                    WHERE hypertable_schema='market_data' AND proc_name IN ('policy_retention','policy_compression')
                    """)).isZero();
        }

        try (var connection = DriverManager.getConnection(jdbcUrl(database), "postgres", "postgres")) {
            assertThat(queryInt(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.schemata
                    WHERE schema_name IN (
                        'identity', 'portfolio', 'reference', 'operations',
                        'market_data', 'fundamentals', 'research'
                    )
                    """)).isEqualTo(7);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*)
                    FROM timescaledb_information.hypertables
                    WHERE hypertable_schema = 'public'
                      AND hypertable_name IN (
                          'tick_data', 'market_bars', 'fundamental_snapshots', 'factor_scores'
                      )
                    """)).isEqualTo(4);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'reference'
                      AND table_name IN (
                          'exchanges', 'issuers', 'instruments', 'instrument_identifiers',
                          'instrument_symbols', 'classification_versions',
                          'issuer_classifications', 'trading_sessions', 'universes',
                          'universe_snapshots', 'universe_memberships'
                      )
                    """)).isEqualTo(11);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*)
                    FROM reference.universes
                    WHERE code IN ('SP500', 'NASDAQ100')
                    """)).isEqualTo(2);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = 'operations' AND table_name IN (
                        'data_providers', 'datasets', 'job_definitions', 'ingestion_runs',
                        'ingestion_run_snapshots', 'ingestion_run_items', 'ingestion_attempts',
                        'ingestion_checkpoints', 'data_quality_issues', 'data_coverage', 'data_watermarks',
                        'source_artifacts', 'outbox_events', 'ingestion_item_events', 'ingestion_item_artifacts', 'kafka_inbox'
                    )
                    """)).isEqualTo(16);
        }
    }

    @Test
    void upgradesFromThePreviousMigrationVersion() throws SQLException {
        var database = createDatabase();

        assertThat(flyway(database, "012").migrate().migrationsExecuted).isEqualTo(12);
        try (var connection = DriverManager.getConnection(jdbcUrl(database), "postgres", "postgres");
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO reference.issuers (legal_name, cik) VALUES ('Upgrade fixture', '0000000001')");
        }

        var upgraded = flyway(database, null);
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
        try (var connection = DriverManager.getConnection(jdbcUrl(database), "postgres", "postgres")) {
            assertThat(queryInt(connection, "SELECT COUNT(*) FROM reference.issuers WHERE cik = '0000000001'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void modelRegistryRetainsFrozenManifestAndRejectsChecksumMismatch() throws SQLException {
        var database = createDatabase();
        flyway(database, null).migrate();
        try (var connection = DriverManager.getConnection(jdbcUrl(database), "postgres", "postgres")) {
            assertThat(queryInt(connection, """
                    SELECT count(*) FROM research.model_versions
                    WHERE model_code='stock-value-quality-momentum' AND semantic_version='1.0.0'
                      AND approval_state='FROZEN_RESEARCH_HYPOTHESIS'
                      AND manifest->'research'->>'final_test_opened'='false'
                      AND length(source_commit)=40 AND length(source_tree_sha256)=64
                      AND manifest_sha256=encode(sha256(convert_to(canonical_manifest,'UTF8')),'hex')
                    """)).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE research.model_versions SET manifest_sha256=repeat('0',64)");
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    private String createDatabase() {
        var database = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(TIMESCALE.getJdbcUrl(), "postgres", "postgres");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create isolated migration test database", exception);
        }

        try (var connection = DriverManager.getConnection(jdbcUrl(database), "postgres", "postgres");
                var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not enable TimescaleDB for migration test", exception);
        }
        return database;
    }

    private Flyway flyway(String database, String target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl(database), "postgres", "postgres")
                .defaultSchema("operations")
                .schemas("operations")
                .createSchemas(true)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
            // An older installation contains only its versioned SQL, not views from future phases.
            try {
                var subset = java.nio.file.Files.createTempDirectory("migration-upgrade-fixture");
                var resources = java.nio.file.Path.of(getClass().getResource("/db/migration").toURI());
                try (var files = java.nio.file.Files.list(resources)) {
                    for (var file : files.toList()) {
                        String name = file.getFileName().toString();
                        if (name.startsWith("V") && Integer.parseInt(name.substring(1,4)) <= Integer.parseInt(target))
                            java.nio.file.Files.copy(file, subset.resolve(name));
                    }
                }
                configuration.locations("filesystem:" + subset);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        return configuration.load();
    }

    private String jdbcUrl(String database) {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                TIMESCALE.getHost(),
                TIMESCALE.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                database);
    }

    private int queryInt(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
