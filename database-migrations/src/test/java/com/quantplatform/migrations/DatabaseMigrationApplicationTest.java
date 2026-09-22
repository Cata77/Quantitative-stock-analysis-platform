package com.quantplatform.migrations;

import static org.assertj.core.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseMigrationApplicationTest {
    @Test void deploymentRejectsMissingAndDevelopmentCredentialsBeforeConnecting() {
        var environment = new HashMap<String, String>();
        environment.put("DATABASE_MIGRATION_USER", "quant_migrator");
        assertThatThrownBy(() -> DatabaseMigrationApplication.configure(environment))
            .isInstanceOf(IllegalStateException.class);
        for (String password : new String[] {"", "postgres_secure_pass",
                "local-quant_migrator-development-only", "change-this-development-secret-before-deploying",
                "integration-test-secret-that-is-at-least-32-bytes"}) {
            environment.put("DATABASE_MIGRATION_PASSWORD", password);
            assertThatThrownBy(() -> DatabaseMigrationApplication.configure(environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("non-development");
        }
        environment.put("DATABASE_MIGRATION_PASSWORD", "isolated-deployment-test-password");
        environment.remove("DATABASE_MIGRATION_USER");
        assertThatThrownBy(() -> DatabaseMigrationApplication.configure(environment))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity");
        environment.put("DATABASE_MIGRATION_USER", "quant_migrator");
        assertThatCode(() -> DatabaseMigrationApplication.configure(environment)).doesNotThrowAnyException();
    }

    @Test void onlyExplicitLocalProfileAllowsDevelopmentDefaults() {
        assertThatCode(() -> DatabaseMigrationApplication.configure(Map.of("SPRING_PROFILES_ACTIVE", "local")))
            .doesNotThrowAnyException();
        for (String profile : new String[] {"", "production", "local,production"}) {
            assertThatThrownBy(() -> DatabaseMigrationApplication.configure(Map.of("SPRING_PROFILES_ACTIVE", profile)))
                .isInstanceOf(IllegalStateException.class);
        }
    }
}
