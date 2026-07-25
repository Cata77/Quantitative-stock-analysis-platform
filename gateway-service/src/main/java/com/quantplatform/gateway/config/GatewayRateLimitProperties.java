package com.quantplatform.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway.rate-limit")
public record GatewayRateLimitProperties(
        boolean enabled,
        int requests,
        Duration window
) {

    public GatewayRateLimitProperties {
        if (requests < 1) {
            throw new IllegalArgumentException("gateway.rate-limit.requests must be positive");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("gateway.rate-limit.window must be positive");
        }
    }
}
