package com.quantplatform.marketdata.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("market-data.providers.alpha-vantage")
public record AlphaVantageProperties(
        URI baseUrl,
        String apiKey
) {

    public AlphaVantageProperties {
        if (baseUrl == null || !"https".equalsIgnoreCase(baseUrl.getScheme())) {
            throw new IllegalArgumentException(
                    "market-data.providers.alpha-vantage.base-url must be an HTTPS URL");
        }
    }
}
