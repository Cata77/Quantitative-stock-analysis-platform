package com.quantplatform.scoring.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.apache.kafka.clients.admin.NewTopic;

import com.quantplatform.scoring.ingestion.MarketDataValidationException;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScoringProperties.class)
class KafkaIngestionConfiguration {

    @Bean
    NewTopic marketDataTopic(ScoringProperties properties) {
        return TopicBuilder.name(properties.inputTopic())
                .partitions(properties.topicPartitions())
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic marketDataDeadLetterTopic(ScoringProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(properties.topicPartitions())
                .replicas(1)
                .build();
    }

    @Bean
    CommonErrorHandler scoringErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            ScoringProperties properties
    ) {
        var recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) ->
                        new TopicPartition(properties.deadLetterTopic(), record.partition()));
        var backOff = new ExponentialBackOffWithMaxRetries(properties.retryAttempts());
        backOff.setInitialInterval(properties.retryBackoff().toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(properties.retryBackoff().multipliedBy(8).toMillis());

        var errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(
                MarketDataValidationException.class,
                DeserializationException.class,
                ConversionException.class);
        return errorHandler;
    }
}
