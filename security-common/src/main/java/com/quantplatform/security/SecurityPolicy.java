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
    /** Deployment checks (P2s: two processes, the collector and the web backend; no Kafka or search cluster). */
    public static void validate(Environment env) {
        if(local(env)) return;
        String app=env.getProperty("spring.application.name","");
        String user=required(env,"spring.datasource.username");
        if(Set.of("postgres","sa","root","quant_migrator").contains(user))
            throw new IllegalStateException("Application database identity must be a restricted service role");
        secret(env.getProperty("spring.datasource.password"),"spring.datasource.password");
        if(app.equals("web-backend")) {
            String origins=env.getProperty("web.allowed-origins","");
            // Empty means no cross-origin access; a configured list must name explicit origins.
            if(origins.contains("*") || origins.contains("localhost") || origins.contains("127.0.0.1"))
                throw new IllegalStateException("Explicit deployment CORS origins are required");
        }
    }
}
