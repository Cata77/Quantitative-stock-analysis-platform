package com.quantplatform.security;

import java.util.*;
import org.springframework.core.env.Environment;

public final class SecurityPolicy {
    private SecurityPolicy() {}
    public static boolean local(Environment env) {
        var profiles=Arrays.asList(env.getActiveProfiles());
        if(profiles.contains("local") && profiles.size()!=1)
            throw new IllegalStateException("The local profile cannot be combined with deployment profiles");
        return profiles.equals(List.of("local"));
    }
    public static String required(Environment env,String name) {
        String value=env.getProperty(name);
        if(value==null||value.isBlank()) throw new IllegalStateException("Required security configuration is missing: "+name);
        return value;
    }
    public static void secret(String value,String name) {
        if(value==null||value.length()<20 || value.toLowerCase(Locale.ROOT).contains("change-this")
                || value.startsWith("local-") || Set.of("postgres_secure_pass","integration-test-secret-that-is-at-least-32-bytes").contains(value))
            throw new IllegalStateException("Missing or development credential: "+name);
    }
    public static void validate(Environment env) {
        if(local(env)) return;
        String app=env.getProperty("spring.application.name","");
        if(!app.equals("gateway-service")) {
            String user=required(env,"spring.datasource.username");
            if(Set.of("postgres","sa","root","quant_migrator").contains(user))
                throw new IllegalStateException("Application database identity must be a restricted service role");
            secret(env.getProperty("spring.datasource.password"),"spring.datasource.password");
        }
        if(app.equals("screener-service")) {
            if(!required(env,"screener.elasticsearch.url").startsWith("https://"))
                throw new IllegalStateException("Non-local Elasticsearch requires HTTPS");
            secret(env.getProperty("screener.elasticsearch.api-key"),"screener.elasticsearch.api-key");
        }
        if(app.equals("gateway-service")) {
            String origins=required(env,"gateway.allowed-origins");
            if(origins.contains("*") || origins.contains("localhost") || origins.contains("127.0.0.1"))
                throw new IllegalStateException("Explicit deployment CORS origins are required");
        }
        if(app.equals("scoring-service") && env.getProperty("scoring.elasticsearch.enabled",Boolean.class,false))
            throw new IllegalStateException("Legacy scoring Elasticsearch writes are local-only; use the authenticated search indexer");
        if(app.equals("market-data-producer")||app.equals("scoring-service")) {
            String protocol=required(env,"spring.kafka.properties.security.protocol");
            if(!Set.of("SSL","SASL_SSL").contains(protocol))
                throw new IllegalStateException("Non-local Kafka requires authenticated TLS configuration");
            if(protocol.equals("SASL_SSL")) required(env,"spring.kafka.properties.sasl.jaas.config");
            else required(env,"spring.kafka.properties.ssl.keystore.location");
        }
    }
}
