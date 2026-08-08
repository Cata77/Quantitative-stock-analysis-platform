package com.quantplatform.marketdata.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class ProviderCredentialValidator implements InitializingBean {

    private final MarketDataProperties marketData;
    private final AlpacaProperties alpaca;
    private final AlphaVantageProperties alphaVantage;

    public ProviderCredentialValidator(
            MarketDataProperties marketData,
            AlpacaProperties alpaca,
            AlphaVantageProperties alphaVantage
    ) {
        this.marketData = marketData;
        this.alpaca = alpaca;
        this.alphaVantage = alphaVantage;
    }

    @Override
    public void afterPropertiesSet() {
        if (!marketData.enabled()) {
            return;
        }
        if ((marketData.latestBarsEnabled() || marketData.historicalBackfillEnabled())
                && (isBlank(alpaca.keyId()) || isBlank(alpaca.secretKey()))) {
            throw new IllegalStateException(
                    "Alpaca credentials are required when price collection is enabled");
        }
        if (marketData.fundamentalsEnabled() && isBlank(alphaVantage.apiKey())) {
            throw new IllegalStateException(
                    "An Alpha Vantage API key is required when fundamentals are enabled");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
