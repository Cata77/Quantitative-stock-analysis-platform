package com.quantplatform.marketdata.config;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("market-data.providers.alpaca")
public record AlpacaProperties(
        URI baseUrl,
        String keyId,
        String secretKey,
        String feed
) {

    private static final Set<String> SUPPORTED_FEEDS =
            Set.of("iex", "sip", "delayed_sip", "boats", "overnight", "otc");

    public AlpacaProperties {
        if (baseUrl == null || !"https".equalsIgnoreCase(baseUrl.getScheme())) {
            throw new IllegalArgumentException(
                    "market-data.providers.alpaca.base-url must be an HTTPS URL");
        }
        if (feed == null || !SUPPORTED_FEEDS.contains(feed)) {
            throw new IllegalArgumentException(
                    "market-data.providers.alpaca.feed is not supported");
        }
    }
}
