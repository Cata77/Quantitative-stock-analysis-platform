package com.quantplatform.migrations;

import java.util.Locale;
import java.util.Map;

import org.flywaydb.core.Flyway;

public final class DatabaseMigrationApplication {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:6543/quant_platform";
    private static final String DEFAULT_USER = "quant_migrator";
    private static final String DEFAULT_PASSWORD = "local-quant_migrator-development-only";

    private DatabaseMigrationApplication() {
    }

    public static void main(String[] args) {
        var environment = System.getenv();
        var flyway = configure(environment);
        var command = args.length == 0 ? "migrate" : args[0].toLowerCase(Locale.ROOT);

        switch (command) {
            case "migrate" -> {
                var result = flyway.migrate();
                flyway.validate();
                var current = flyway.info().current();
                System.out.printf(
                        "Database migration complete: %d migration(s) applied; schema is at %s.%n",
                        result.migrationsExecuted,
                        current == null ? "<empty>" : current.getVersion());
            }
            case "validate" -> {
                flyway.validate();
                System.out.println("Database migrations are valid.");
            }
            case "info" -> {
                var current = flyway.info().current();
                System.out.println(current == null
                        ? "Database has no applied migrations."
                        : "Current database migration: " + current.getVersion());
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported command '%s'; expected migrate, validate, or info."
                            .formatted(command));
        }
    }

    static Flyway configure(Map<String, String> environment) {
        boolean local="local".equals(environment.get("SPRING_PROFILES_ACTIVE"));
        if(!local) {
            String password=environment.get("DATABASE_MIGRATION_PASSWORD");
            if(password==null||password.length()<20||password.startsWith("local-")||password.equals("postgres_secure_pass")
                    ||password.toLowerCase(Locale.ROOT).contains("change-this")
                    ||password.equals("integration-test-secret-that-is-at-least-32-bytes"))
                throw new IllegalStateException("An explicit non-development migration password is required");
            if(environment.getOrDefault("DATABASE_MIGRATION_USER","").isBlank())
                throw new IllegalStateException("An explicit migration identity is required");
        }
        return Flyway.configure()
                .dataSource(
                        environment.getOrDefault("DATABASE_MIGRATION_URL", DEFAULT_URL),
                        environment.getOrDefault("DATABASE_MIGRATION_USER", DEFAULT_USER),
                        environment.getOrDefault("DATABASE_MIGRATION_PASSWORD", DEFAULT_PASSWORD))
                .defaultSchema("operations")
                .schemas("operations")
                .createSchemas(true)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .locations("classpath:db/migration")
                .load();
    }
}
