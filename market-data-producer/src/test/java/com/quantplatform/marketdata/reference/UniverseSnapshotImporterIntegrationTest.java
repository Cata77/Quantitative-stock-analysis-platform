package com.quantplatform.marketdata.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class UniverseSnapshotImporterIntegrationTest {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.29.1-pg18")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> TIMESCALE = new PostgreSQLContainer<>(TIMESCALE_IMAGE)
            .withDatabaseName("postgres")
            .withUsername("postgres")
            .withPassword("postgres");

    @Test
    void importsSnapshotsIdempotentlyAndPreservesIdentityHistory() throws SQLException {
        var database = createDatabase();
        var dataSource = new DriverManagerDataSource(jdbcUrl(database), "postgres", "postgres");
        var importer = new UniverseSnapshotImporter(
                dataSource,
                new DataSourceTransactionManager(dataSource));

        var sp500Members = List.of(
                member("META", "Meta Platforms, Inc.", "1326801", "FIGI-META", "CLASS_A", true),
                member("GOOGL", "Alphabet Inc.", "1652044", "FIGI-GOOGL", "CLASS_A", true),
                member("GOOG", "Alphabet Inc.", "1652044", "FIGI-GOOG", "CLASS_C", false),
                member("MSFT", "Microsoft Corporation", "789019", "FIGI-MSFT", "COMMON", true));
        var sp500 = request(UniverseCode.SP500, LocalDate.of(2026, 8, 23), sp500Members);

        var first = importer.importSnapshot(sp500);
        var repeated = importer.importSnapshot(sp500);

        assertThat(first.reusedSnapshot()).isFalse();
        assertThat(first.issuersCreated()).isEqualTo(3);
        assertThat(first.instrumentsCreated()).isEqualTo(4);
        assertThat(repeated.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(repeated.reusedSnapshot()).isTrue();

        var nasdaqMembers = List.of(
                member("META", "Meta Platforms, Inc.", "1326801", "FIGI-META", "CLASS_A", true),
                member("GOOGL", "Alphabet Inc.", "1652044", "FIGI-GOOGL", "CLASS_A", true),
                member("GOOG", "Alphabet Inc.", "1652044", "FIGI-GOOG", "CLASS_C", false),
                member("MSFT", "Microsoft Corporation", "789019", "FIGI-MSFT", "COMMON", true),
                member("TSLA", "Tesla, Inc.", "1318605", "FIGI-TSLA", "COMMON", true));
        var nasdaq = importer.importSnapshot(
                request(UniverseCode.NASDAQ100, LocalDate.of(2026, 8, 23), nasdaqMembers));

        try (var connection = DriverManager.getConnection(jdbcUrl(database), "postgres", "postgres")) {
            assertThat(queryInt(connection, "SELECT COUNT(*) FROM reference.universe_snapshots"))
                    .isEqualTo(2);
            assertThat(queryInt(connection, "SELECT COUNT(*) FROM reference.issuers"))
                    .isEqualTo(4);
            assertThat(queryInt(connection, "SELECT COUNT(*) FROM reference.instruments"))
                    .isEqualTo(5);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*)
                    FROM reference.instruments instrument
                    JOIN reference.issuers issuer ON issuer.issuer_id = instrument.issuer_id
                    WHERE issuer.cik = '0001652044'
                    """)).isEqualTo(2);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*)
                    FROM reference.research_universe_for_snapshots(
                        '%s'::uuid,
                        '%s'::uuid
                    )
                    """.formatted(first.snapshotId(), nasdaq.snapshotId()))).isEqualTo(5);
        }

        var renamedMembers = List.of(
                member("MVRS", "Meta Platforms, Inc.", "1326801", "FIGI-META", "CLASS_A", true),
                member("GOOGL", "Alphabet Inc.", "1652044", "FIGI-GOOGL", "CLASS_A", true),
                member("GOOG", "Alphabet Inc.", "1652044", "FIGI-GOOG", "CLASS_C", false),
                member("TSLA", "Tesla, Inc.", "1318605", "FIGI-TSLA", "COMMON", true));
        var renamed = importer.importSnapshot(
                request(UniverseCode.SP500, LocalDate.of(2026, 9, 1), renamedMembers));

        assertThat(renamed.issuersCreated()).isZero();
        assertThat(renamed.instrumentsCreated()).isZero();
        try (var connection = DriverManager.getConnection(jdbcUrl(database), "postgres", "postgres")) {
            assertThat(queryInt(connection, """
                    SELECT COUNT(*)
                    FROM reference.instrument_symbols symbol
                    JOIN reference.instrument_identifiers identifier
                      ON identifier.instrument_id = symbol.instrument_id
                    WHERE identifier.identifier_scheme = 'FIGI'
                      AND identifier.identifier_value = 'FIGI-META'
                    """)).isEqualTo(2);
            assertThat(queryString(connection, """
                    SELECT symbol.symbol
                    FROM reference.instrument_symbols symbol
                    JOIN reference.instrument_identifiers identifier
                      ON identifier.instrument_id = symbol.instrument_id
                    WHERE identifier.identifier_scheme = 'FIGI'
                      AND identifier.identifier_value = 'FIGI-META'
                      AND symbol.effective_to IS NULL
                    """)).isEqualTo("MVRS");
            assertThat(queryInt(connection, """
                    SELECT COUNT(*)
                    FROM reference.universe_snapshots snapshot
                    JOIN reference.universes universe ON universe.universe_id = snapshot.universe_id
                    WHERE universe.code = 'SP500'
                    """)).isEqualTo(2);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*)
                    FROM reference.universe_memberships membership
                    JOIN reference.instrument_identifiers identifier
                      ON identifier.instrument_id = membership.instrument_id
                    WHERE membership.universe_snapshot_id = '%s'::uuid
                      AND identifier.identifier_scheme = 'FIGI'
                      AND identifier.identifier_value = 'FIGI-MSFT'
                    """.formatted(first.snapshotId()))).isEqualTo(1);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*)
                    FROM reference.universe_memberships membership
                    JOIN reference.instrument_identifiers identifier
                      ON identifier.instrument_id = membership.instrument_id
                    WHERE membership.universe_snapshot_id = '%s'::uuid
                      AND identifier.identifier_scheme = 'FIGI'
                      AND identifier.identifier_value = 'FIGI-MSFT'
                    """.formatted(renamed.snapshotId()))).isZero();
        }
    }

    private UniverseSnapshotImportRequest request(
            UniverseCode universeCode,
            LocalDate effectiveDate,
            List<UniverseMemberInput> members
    ) {
        var observedAt = effectiveDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        return new UniverseSnapshotImportRequest(
                universeCode,
                effectiveDate,
                observedAt,
                observedAt,
                "TEST_DATED_SNAPSHOT",
                "test://" + universeCode.name().toLowerCase() + "/" + effectiveDate,
                UniverseImportMode.CURRENT_SNAPSHOT_FORWARD,
                SnapshotCompleteness.COMPLETE,
                members.size(),
                null,
                null,
                members);
    }

    private UniverseMemberInput member(
            String symbol,
            String legalName,
            String cik,
            String figi,
            String shareClass,
            boolean primaryLiquidClass
    ) {
        return new UniverseMemberInput(
                symbol,
                legalName,
                cik,
                figi,
                "XNAS",
                "COMMON_STOCK",
                shareClass,
                "USD",
                "US",
                null,
                null,
                null,
                primaryLiquidClass);
    }

    private String createDatabase() {
        var database = "reference_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(TIMESCALE.getJdbcUrl(), "postgres", "postgres");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create isolated reference-data database", exception);
        }

        try (var connection = DriverManager.getConnection(jdbcUrl(database), "postgres", "postgres");
                var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not enable TimescaleDB", exception);
        }

        var flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), "postgres", "postgres")
                .defaultSchema("operations")
                .schemas("operations")
                .createSchemas(true)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .locations("classpath:db/migration")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(19);
        return database;
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

    private String queryString(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
