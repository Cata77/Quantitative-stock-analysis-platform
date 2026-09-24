package com.quantplatform.observability;

import javax.sql.DataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration(afterName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"})
@EnableScheduling
public class OperationalAutoConfiguration {
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(name="operations.telemetry.enabled",havingValue="true")
    JdbcOperationalMetrics operationalMetrics(DataSource source,MeterRegistry registry,Environment env) {
        return new JdbcOperationalMetrics(source,registry,env.getRequiredProperty("spring.application.name"));
    }
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(name="operations.readiness.enabled",havingValue="true")
    SchemaReadinessHealthIndicator schemaReadiness(DataSource source,Environment env) {
        return new SchemaReadinessHealthIndicator(source,env.getRequiredProperty("spring.application.name"));
    }
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnClass(name="org.springframework.boot.webclient.WebClientCustomizer")
    static class ProviderClients {
        @Bean org.springframework.boot.webclient.WebClientCustomizer providerTelemetry(MeterRegistry registry) {
            return builder -> builder.filter(new ProviderTelemetryFilter(registry));
        }
    }
}
