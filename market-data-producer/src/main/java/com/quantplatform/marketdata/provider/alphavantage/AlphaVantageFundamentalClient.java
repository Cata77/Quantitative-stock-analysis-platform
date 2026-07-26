package com.quantplatform.marketdata.provider.alphavantage;

import com.quantplatform.marketdata.config.AlphaVantageProperties;
import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.event.FundamentalSnapshot;
import com.quantplatform.marketdata.provider.MarketDataProviderException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

@Component
public class AlphaVantageFundamentalClient {

    public static final String PROVIDER = "alpha-vantage";

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final AlphaVantageProperties alphaVantage;
    private final MarketDataProperties marketData;

    public AlphaVantageFundamentalClient(
            WebClient.Builder webClientBuilder,
            AlphaVantageProperties alphaVantage,
            MarketDataProperties marketData
    ) {
        this.webClient = webClientBuilder.clone()
                .baseUrl(alphaVantage.baseUrl().toString())
                .build();
        this.alphaVantage = alphaVantage;
        this.marketData = marketData;
    }

    public FundamentalSnapshot fetchCompanyOverview(String symbol) {
        Map<String, Object> response;
        try {
            response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/query")
                            .queryParam("function", "OVERVIEW")
                            .queryParam("symbol", symbol)
                            .queryParam("apikey", nullToEmpty(alphaVantage.apiKey()))
                            .build())
                    .retrieve()
                    .bodyToMono(RESPONSE_TYPE)
                    .block(marketData.providerTimeout());
        } catch (WebClientException exception) {
            throw new MarketDataProviderException(
                    "Alpha Vantage company overview request failed for " + symbol,
                    exception);
        }
        validateResponse(symbol, response);
        return new FundamentalSnapshot(
                text(response, "Name"),
                text(response, "AssetType"),
                text(response, "Exchange"),
                text(response, "Currency"),
                text(response, "Country"),
                text(response, "Sector"),
                text(response, "Industry"),
                date(response, "LatestQuarter"),
                decimal(response, "MarketCapitalization"),
                decimal(response, "RevenueTTM"),
                decimal(response, "EBITDA"),
                decimal(response, "PERatio"),
                decimal(response, "PEGRatio"),
                decimal(response, "PriceToBookRatio"),
                decimal(response, "EPS"),
                decimal(response, "ProfitMargin"),
                decimal(response, "OperatingMarginTTM"),
                decimal(response, "ReturnOnAssetsTTM"),
                decimal(response, "ReturnOnEquityTTM"),
                decimal(response, "QuarterlyRevenueGrowthYOY"),
                decimal(response, "QuarterlyEarningsGrowthYOY"),
                decimal(response, "AnalystTargetPrice"),
                decimal(response, "Beta"));
    }

    private void validateResponse(String symbol, Map<String, Object> response) {
        if (response == null || response.isEmpty()) {
            throw new MarketDataProviderException(
                    "Alpha Vantage returned no company overview for " + symbol);
        }
        for (String errorKey : new String[]{"Error Message", "Information", "Note"}) {
            String message = text(response, errorKey);
            if (message != null) {
                throw new MarketDataProviderException(
                        "Alpha Vantage rejected " + symbol + ": " + message);
            }
        }
        if (text(response, "Symbol") == null || text(response, "Name") == null) {
            throw new MarketDataProviderException(
                    "Alpha Vantage returned an incomplete company overview for " + symbol);
        }
    }

    private static String text(Map<String, Object> response, String key) {
        Object value = response.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() || "None".equalsIgnoreCase(text) || "-".equals(text)
                ? null
                : text;
    }

    private static BigDecimal decimal(Map<String, Object> response, String key) {
        String value = text(response, key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new MarketDataProviderException(
                    "Alpha Vantage field " + key + " is not numeric", exception);
        }
    }

    private static LocalDate date(Map<String, Object> response, String key) {
        String value = text(response, key);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new MarketDataProviderException(
                    "Alpha Vantage field " + key + " is not an ISO date", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
