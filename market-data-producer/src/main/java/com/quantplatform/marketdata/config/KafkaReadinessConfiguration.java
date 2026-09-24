package com.quantplatform.marketdata.config;

import org.springframework.context.annotation.*;
import org.springframework.boot.health.contributor.*;
import org.springframework.kafka.core.KafkaAdmin;
import org.apache.kafka.clients.admin.AdminClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods=false)
class KafkaReadinessConfiguration {
    @Bean HealthIndicator kafkaReadiness(KafkaAdmin kafka) {
        return () -> {
            var configuration=new java.util.HashMap<String,Object>(kafka.getConfigurationProperties());
            configuration.put("request.timeout.ms",3000);
            configuration.put("default.api.timeout.ms",3000);
            AdminClient client=AdminClient.create(configuration);
            try {
                client.describeCluster().clusterId().get(3,TimeUnit.SECONDS);
                return Health.up().build();
            } catch(Exception failure) {
                if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return Health.down().withDetail("reason","KAFKA_UNAVAILABLE").build();
            } finally { client.close(Duration.ZERO); }
        };
    }
}
