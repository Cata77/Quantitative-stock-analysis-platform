package com.quantplatform.marketdata.provider.tradier;

import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.config.TradierProperties;
import com.quantplatform.marketdata.event.OptionSnapshot;
import com.quantplatform.marketdata.event.OptionType;
import com.quantplatform.marketdata.provider.MarketDataProviderException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

@Component
public class TradierOptionMarketClient {

    public static final String PROVIDER = "tradier";

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final MarketDataProperties marketData;
    private final Clock clock;

    public TradierOptionMarketClient(
            WebClient.Builder webClientBuilder,
            TradierProperties tradier,
            MarketDataProperties marketData,
            Clock clock
    ) {
        WebClient.Builder tradierClient = webClientBuilder.clone()
                .baseUrl(tradier.baseUrl().toString())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (tradier.token() != null && !tradier.token().isBlank()) {
            tradierClient.defaultHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + tradier.token());
        }
        this.webClient = tradierClient.build();
        this.marketData = marketData;
        this.clock = clock;
    }

    public List<OptionSnapshot> fetchNearestOptionChain(String symbol) {
        LocalDate expiration = fetchExpirations(symbol).stream()
                .filter(date -> !date.isBefore(LocalDate.now(clock)))
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new MarketDataProviderException(
                        "Tradier returned no future option expiration for " + symbol));
        Map<String, Object> response = get(
                symbol,
                "/markets/options/chains",
                "expiration",
                expiration.toString());
        return parseOptionChain(symbol, response);
    }

    private List<LocalDate> fetchExpirations(String symbol) {
        Map<String, Object> response = get(
                symbol,
                "/markets/options/expirations",
                "includeAllRoots",
                "true");
        Object expirations = response.get("expirations");
        if (!(expirations instanceof Map<?, ?> expirationMap)) {
            return List.of();
        }
        Object dates = expirationMap.get("date");
        List<?> rawDates = dates instanceof List<?> list
                ? list
                : dates == null ? List.of() : List.of(dates);
        try {
            return rawDates.stream()
                    .map(Object::toString)
                    .map(LocalDate::parse)
                    .toList();
        } catch (DateTimeParseException exception) {
            throw new MarketDataProviderException(
                    "Tradier returned an invalid expiration date for " + symbol,
                    exception);
        }
    }

    private Map<String, Object> get(
            String symbol,
            String path,
            String extraParameter,
            String extraValue
    ) {
        try {
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(path)
                            .queryParam("symbol", symbol)
                            .queryParam(extraParameter, extraValue)
                            .queryParamIfPresent(
                                    "greeks",
                                    "/markets/options/chains".equals(path)
                                            ? java.util.Optional.of(true)
                                            : java.util.Optional.empty())
                            .build())
                    .retrieve()
                    .bodyToMono(RESPONSE_TYPE)
                    .block(marketData.providerTimeout());
            if (response == null) {
                throw new MarketDataProviderException(
                        "Tradier returned no response for " + symbol);
            }
            return response;
        } catch (WebClientException exception) {
            throw new MarketDataProviderException(
                    "Tradier request failed for " + symbol, exception);
        }
    }

    private List<OptionSnapshot> parseOptionChain(
            String symbol,
            Map<String, Object> response
    ) {
        Object options = response.get("options");
        if (!(options instanceof Map<?, ?> optionsMap)) {
            return List.of();
        }
        Object optionValue = optionsMap.get("option");
        List<?> rawOptions = optionValue instanceof List<?> list
                ? list
                : optionValue == null ? List.of() : List.of(optionValue);

        List<OptionSnapshot> snapshots = new ArrayList<>();
        for (Object rawOption : rawOptions) {
            if (rawOption instanceof Map<?, ?> option) {
                OptionSnapshot snapshot = parseOption(option);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
        }
        return List.copyOf(snapshots);
    }

    private OptionSnapshot parseOption(Map<?, ?> option) {
        Map<?, ?> greeks = option.get("greeks") instanceof Map<?, ?> values
                ? values
                : Map.of();
        BigDecimal impliedVolatility = firstNonNull(
                decimal(greeks, "smv_vol"),
                decimal(greeks, "mid_iv"));
        BigDecimal delta = decimal(greeks, "delta");
        BigDecimal gamma = decimal(greeks, "gamma");
        BigDecimal theta = decimal(greeks, "theta");
        BigDecimal vega = decimal(greeks, "vega");
        BigDecimal rho = decimal(greeks, "rho");
        if (impliedVolatility == null
                || delta == null
                || gamma == null
                || theta == null
                || vega == null
                || rho == null) {
            return null;
        }

        BigDecimal bid = defaultZero(decimal(option, "bid"));
        BigDecimal ask = defaultZero(decimal(option, "ask"));
        BigDecimal lastPrice = decimal(option, "last");
        if (lastPrice == null) {
            lastPrice = midpoint(bid, ask);
        }

        String optionType = requiredText(option, "option_type");
        return new OptionSnapshot(
                requiredText(option, "symbol"),
                tradeTime(option),
                requiredDate(option, "expiration_date"),
                requiredDecimal(option, "strike"),
                OptionType.valueOf(optionType.toUpperCase(java.util.Locale.ROOT)),
                defaultZero(lastPrice),
                bid,
                ask,
                defaultLong(option, "volume"),
                defaultLong(option, "open_interest"),
                impliedVolatility,
                delta,
                gamma,
                theta,
                vega,
                rho);
    }

    private Instant tradeTime(Map<?, ?> option) {
        Object value = option.get("trade_date");
        if (value instanceof Number number && number.longValue() > 0) {
            return Instant.ofEpochMilli(number.longValue());
        }
        if (value != null) {
            try {
                long epochMillis = Long.parseLong(value.toString());
                if (epochMillis > 0) {
                    return Instant.ofEpochMilli(epochMillis);
                }
            } catch (NumberFormatException ignored) {
                // Use collection time when the provider omits a usable trade epoch.
            }
        }
        return clock.instant();
    }

    private static BigDecimal midpoint(BigDecimal bid, BigDecimal ask) {
        if (bid.signum() == 0 && ask.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return bid.add(ask).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    }

    private static String requiredText(Map<?, ?> values, String key) {
        String value = text(values, key);
        if (value == null) {
            throw new MarketDataProviderException("Tradier field " + key + " is required");
        }
        return value;
    }

    private static String text(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static BigDecimal requiredDecimal(Map<?, ?> values, String key) {
        BigDecimal value = decimal(values, key);
        if (value == null) {
            throw new MarketDataProviderException("Tradier field " + key + " is required");
        }
        return value;
    }

    private static BigDecimal decimal(Map<?, ?> values, String key) {
        String value = text(values, key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new MarketDataProviderException(
                    "Tradier field " + key + " is not numeric", exception);
        }
    }

    private static LocalDate requiredDate(Map<?, ?> values, String key) {
        String value = requiredText(values, key);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new MarketDataProviderException(
                    "Tradier field " + key + " is not an ISO date", exception);
        }
    }

    private static long defaultLong(Map<?, ?> values, String key) {
        String value = text(values, key);
        if (value == null) {
            return 0;
        }
        try {
            return new BigDecimal(value).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new MarketDataProviderException(
                    "Tradier field " + key + " is not an integer", exception);
        }
    }

    private static BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first != null ? first : second;
    }
}
