package com.quantplatform.marketdata.operations;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ingestion.delivery")
public record DeliveryProperties(@DefaultValue("10") int maxAttempts,
                                 @DefaultValue("50") int batchSize,
                                 @DefaultValue("2m") Duration lease,
                                 @DefaultValue("15s") Duration sendTimeout,
                                 @DefaultValue("5s") Duration retryBackoff) {
    public DeliveryProperties {
        if (maxAttempts < 1 || batchSize < 1 || batchSize > 1000 || sendTimeout.isNegative()
                || sendTimeout.isZero() || lease.compareTo(sendTimeout.multipliedBy(2)) < 0
                || retryBackoff.isNegative() || retryBackoff.isZero()) {
            throw new IllegalArgumentException("invalid delivery retry/lease limits");
        }
    }
}
