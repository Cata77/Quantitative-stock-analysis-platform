package com.quantplatform.observability;

import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Required migrated objects and SELECT permissions, without exposing Flyway history. */
public final class SchemaReadinessHealthIndicator implements HealthIndicator {
    private final DataSource source;
    private final String table;
    public SchemaReadinessHealthIndicator(DataSource source,String application) {
        this.source=source;
        table=switch(application) {
            case "auth-service" -> "public.users";
            case "portfolio-service" -> "public.portfolios";
            case "market-data-producer" -> "operations.outbox_events";
            case "scoring-service" -> "research.scoring_runs";
            case "screener-service" -> "operations.search_rebuild_checkpoints";
            default -> throw new IllegalArgumentException("Unknown database application");
        };
    }
    @Override public Health health() {
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.executeQuery("SELECT 1 FROM "+table+" LIMIT 0").close();
            return Health.up().build();
        } catch(Exception failure) { return Health.down().withDetail("reason","DATABASE_OR_SCHEMA_UNAVAILABLE").build(); }
    }
}
