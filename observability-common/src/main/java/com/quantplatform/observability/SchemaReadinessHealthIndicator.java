package com.quantplatform.observability;

import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Required migrated objects and SELECT permissions, without exposing Flyway history. */
public final class SchemaReadinessHealthIndicator implements HealthIndicator {
    private final DataSource source;
    private final java.util.List<String> tables;
    public SchemaReadinessHealthIndicator(DataSource source,String application) {
        this.source=source;
        tables=switch(application) {
            // P2s: the collector also owns canonical processing; one backend serves accounts, holdings and screener.
            case "market-data-producer" -> java.util.List.of("operations.outbox_events","operations.kafka_inbox");
            case "web-backend" -> java.util.List.of("public.users","public.portfolios","research.stock_scores");
            default -> throw new IllegalArgumentException("Unknown database application");
        };
    }
    @Override public Health health() {
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.setQueryTimeout(5);
            for(String table:tables) statement.executeQuery("SELECT 1 FROM "+table+" LIMIT 0").close();
            return Health.up().build();
        } catch(Exception failure) { return Health.down().withDetail("reason","DATABASE_OR_SCHEMA_UNAVAILABLE").build(); }
    }
}
