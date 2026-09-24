package com.quantplatform.observability;

import static org.assertj.core.api.Assertions.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class OperationalMetricsIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.29.1-pg18").asCompatibleSubstituteFor("postgres"));
    static PGSimpleDataSource source(String role) {
        var source=new PGSimpleDataSource();source.setURL(DB.getJdbcUrl());
        source.setUser(role);source.setPassword(role.equals(DB.getUsername())?DB.getPassword():"isolated-telemetry-password");return source;
    }
    @BeforeAll static void setup() throws Exception {
        try(var c=source(DB.getUsername()).getConnection();var s=c.createStatement()) { s.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE"); }
        Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).defaultSchema("operations").schemas("operations").load().migrate();
        try(var c=source(DB.getUsername()).getConnection();var s=c.createStatement()) {
            for(String role:new String[]{"quant_ingestion","quant_scoring","quant_screener","quant_auth"})
                s.execute("ALTER ROLE "+role+" LOGIN PASSWORD 'isolated-telemetry-password'");
            s.execute("INSERT INTO operations.data_providers(code,name,license_notes) VALUES('fixture','Fixture','fixture')");
            s.execute("INSERT INTO operations.datasets(provider_id,code,version,license_notes) SELECT provider_id,'fixture','1','fixture' FROM operations.data_providers");
            s.execute("INSERT INTO operations.data_quality_issues(dataset_id,severity,issue_type,affected_key,evidence) SELECT dataset_id,'BLOCKING','GAP','fixture','{}' FROM operations.datasets");
        }
    }
    @Test void telemetryRunsWithActualServiceGrantsAndDoesNotInventCoverage() {
        for(String application:new String[]{"market-data-producer","scoring-service","screener-service"}) {
            String role=application.equals("market-data-producer")?"quant_ingestion":application.equals("scoring-service")?"quant_scoring":"quant_screener";
            var registry=new SimpleMeterRegistry();
            var metrics=new JdbcOperationalMetrics(source(role),registry,application);metrics.refresh();
            assertThat(registry.get("quant.telemetry.up").gauge().value()).isEqualTo(1);
            if(!application.equals("screener-service")) {
                assertThat(registry.get("quant.quality.blocking").gauge().value()).isEqualTo(1);
                assertThat(registry.get("quant.watermark.lag.seconds").gauge().value()).isNaN();
            } else {
                assertThat(registry.get("quant.publication.present").gauge().value()).isZero();
                assertThat(registry.get("quant.universe.coverage").gauge().value()).isNaN();
            }
            assertThat(new SchemaReadinessHealthIndicator(source(role),application).health().getStatus().getCode()).isEqualTo("UP");
        }
    }
    @Test void autoConfigurationInstallsReadinessAndTelemetry() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(OperationalAutoConfiguration.class))
            .withBean(javax.sql.DataSource.class,()->source("quant_scoring"))
            .withBean(io.micrometer.core.instrument.MeterRegistry.class,SimpleMeterRegistry::new)
            .withPropertyValues("spring.application.name=scoring-service","operations.telemetry.enabled=true","operations.readiness.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(JdbcOperationalMetrics.class)
                    .hasSingleBean(SchemaReadinessHealthIndicator.class);
                assertThat(context.getBean(SchemaReadinessHealthIndicator.class).health().getStatus().getCode()).isEqualTo("UP");
            });
    }
    @Test void permissionFailureIsNotReportedAsEmptyHealthyMetricsOrReady() {
        var registry=new SimpleMeterRegistry();
        new JdbcOperationalMetrics(source("quant_auth"),registry,"scoring-service").refresh();
        assertThat(registry.get("quant.telemetry.up").gauge().value()).isZero();
        assertThat(registry.get("quant.outbox.pending").gauge().value()).isNaN();
        assertThat(new SchemaReadinessHealthIndicator(source("quant_auth"),"scoring-service").health().getStatus().getCode()).isEqualTo("DOWN");
    }
}
