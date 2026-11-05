package com.quantplatform.marketdata.operations;

import com.quantplatform.ingestion.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Registers immutable job configuration without updating existing reference rows. */
final class IngestionJobRegistry {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    IngestionJobRegistry(DataSource source, PlatformTransactionManager manager) {
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(manager);
    }

    UUID register(String providerCode, String datasetCode, String eventType,
            Map<String, Object> configuration, String topic, int maxAttempts) {
        return transactions.execute(status -> {
            jdbc.sql("""
                    INSERT INTO operations.data_providers (code, name, license_notes)
                    VALUES (:code, :code, 'Development adapter; account permissions and provider terms govern use')
                    ON CONFLICT (code) DO NOTHING
                    """).param("code", providerCode).update();
            // A separate READ COMMITTED statement also sees a concurrent insert after
            // ON CONFLICT has waited for it. No no-op UPDATE locks an existing row.
            UUID provider = jdbc.sql("SELECT provider_id FROM operations.data_providers WHERE code=:code")
                    .param("code", providerCode).query(UUID.class).single();
            jdbc.sql("""
                    INSERT INTO operations.datasets (provider_id, code, version, license_notes)
                    VALUES (:provider, :code, '1', 'Raw development observations; not a licensed historical research dataset')
                    ON CONFLICT (provider_id, code, version) DO NOTHING
                    """).param("provider", provider).param("code", datasetCode).update();
            UUID dataset = jdbc.sql("""
                    SELECT dataset_id FROM operations.datasets
                    WHERE provider_id=:provider AND code=:code AND version='1'
                    """).param("provider", provider).param("code", datasetCode).query(UUID.class).single();
            var config = new LinkedHashMap<>(configuration);
            config.put("eventType", eventType);
            config.put("topic", topic);
            config.put("maxAttempts", maxAttempts);
            String json = CanonicalJson.write(config);
            String code = providerCode + ":" + datasetCode;
            String version = CanonicalJson.sha256(json).substring(0, 40);
            jdbc.sql("""
                    INSERT INTO operations.job_definitions (dataset_id, code, version, configuration, max_attempts)
                    VALUES (:dataset, :code, :version, CAST(:config AS jsonb), :max)
                    ON CONFLICT (code, version) DO NOTHING
                    """).param("dataset", dataset).param("code", code).param("version", version)
                    .param("config", json).param("max", maxAttempts).update();
            return jdbc.sql("SELECT job_definition_id FROM operations.job_definitions WHERE code=:code AND version=:version")
                    .param("code", code).param("version", version).query(UUID.class).single();
        });
    }
}
