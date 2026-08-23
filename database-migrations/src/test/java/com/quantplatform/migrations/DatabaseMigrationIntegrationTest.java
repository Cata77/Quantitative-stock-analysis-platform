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

@Testcontainers(disabledWithoutDocker = true)
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

        assertThat(firstRun.migrationsExecuted).isEqualTo(2);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

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
        }
    }

    @Test
    void upgradesFromThePreviousMigrationVersion() {
        var database = createDatabase();

        assertThat(flyway(database, "001").migrate().migrationsExecuted).isEqualTo(1);

        var upgraded = flyway(database, null);
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
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
