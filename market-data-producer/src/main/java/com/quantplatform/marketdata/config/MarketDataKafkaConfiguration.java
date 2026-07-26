package com.quantplatform.marketdata.config;

import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class MarketDataKafkaConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "market-data", name = "enabled", havingValue = "true")
    NewTopic marketDataEventsTopic(MarketDataProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.topicPartitions())
                .replicas(1)
                .build();
    }

    @Bean
    Clock marketDataClock() {
        return Clock.systemUTC();
    }
}
