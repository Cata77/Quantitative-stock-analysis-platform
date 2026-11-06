package com.quantplatform.marketdata.canonical;

import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.operations.IngestionProperties;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Canonical processing beans of the collector (P2s, stage B). The inbox consumer name stays
 * {@code ingestion.canonical-consumer} ({@code scoring-service-v2}) so that observations accepted by
 * the former scoring-service are recognized and never written twice.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CanonicalProperties.class)
public class CanonicalConfiguration {

    @Bean
    DurableObservationProcessor durableObservationProcessor(DataSource source, PlatformTransactionManager manager,
            IngestionProperties ingestion) {
        return new DurableObservationProcessor(source, manager, new LegacyProjection(source), ingestion.canonicalConsumer());
    }

    @Bean
    DeadLetterService deadLetterService(DataSource source, PlatformTransactionManager manager,
            MarketDataProperties market, IngestionProperties ingestion) {
        return new DeadLetterService(source, manager, market.topic(), ingestion.canonicalConsumer());
    }

    @Bean
    OutboxDeliveryWorker outboxDeliveryWorker(DataSource source, PlatformTransactionManager manager,
            DurableObservationProcessor processor, DeadLetterService deadLetters, MarketDataProperties market,
            CanonicalProperties properties) {
        return new OutboxDeliveryWorker(source, manager, processor, deadLetters, market.topic(),
                properties.retryAttempts(), properties.retryBackoff(), properties.lease());
    }

    @Bean
    @ConditionalOnProperty(name = "canonical.delivery-enabled", havingValue = "true", matchIfMissing = true)
    PendingDelivery pendingDelivery(OutboxDeliveryWorker worker) {
        return new PendingDelivery(worker);
    }

    @Bean
    DeadLetterCommand deadLetterCommand(CanonicalProperties properties, DeadLetterService deadLetters) {
        return new DeadLetterCommand(properties.command(), deadLetters);
    }

    /** Drains events left PENDING, then refreshes coverage when something new was accepted. */
    public static class PendingDelivery {
        private final OutboxDeliveryWorker worker;
        PendingDelivery(OutboxDeliveryWorker worker) { this.worker = worker; }

        @Scheduled(fixedDelayString = "${canonical.delivery-interval-ms:1000}",
                initialDelayString = "${canonical.delivery-interval-ms:1000}")
        public void poll() {
            try {
                if (worker.drain(500) > 0) worker.processor().reconcileCoverage();
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(getClass()).warn("Pending canonical delivery will retry: {}",
                        failure.getClass().getSimpleName());
            }
        }
    }

    /** Operator commands of the dead-letter quarantine (formerly scoring-service dlq-inspect/dlq-replay). */
    public static class DeadLetterCommand implements ApplicationRunner {
        private final CanonicalProperties.Command command;
        private final DeadLetterService deadLetters;
        private int exitCode;
        DeadLetterCommand(CanonicalProperties.Command command, DeadLetterService deadLetters) {
            this.command = command;
            this.deadLetters = deadLetters;
        }
        @Override
        public void run(ApplicationArguments arguments) {
            switch (command.operation()) {
                case "serve" -> { }
                case "dlq-inspect" -> System.out.println(com.quantplatform.ingestion.CanonicalJson.MAPPER
                        .writeValueAsString(deadLetters.inspect(command.limit())));
                case "dlq-replay" -> exitCode = deadLetters.replay(command.deadLetterId(), command.replayId(),
                        command.operator(), command.reason()) ? 0 : 2;
                default -> throw new IllegalArgumentException("unknown command");
            }
        }
        public int exitCode() { return exitCode; }
    }
}
