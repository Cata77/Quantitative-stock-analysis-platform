package com.quantplatform.marketdata.operations;

import java.util.HashMap;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeliveryProperties.class)
public class DeliveryConfiguration {
    @Bean
    KafkaTemplate<String, String> outboxKafkaTemplate(@Value("${spring.kafka.bootstrap-servers}") String brokers) {
        var config = new HashMap<String, Object>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 15_000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }
}
