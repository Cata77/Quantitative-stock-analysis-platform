package com.quantplatform.marketdata.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("market-data.providers.tradier")
public record TradierProperties(
        URI baseUrl,
        String token
) {

    public TradierProperties {
        if (baseUrl == null || !"https".equalsIgnoreCase(baseUrl.getScheme())) {
            throw new IllegalArgumentException(
                    "market-data.providers.tradier.base-url must be an HTTPS URL");
        }
    }
}
