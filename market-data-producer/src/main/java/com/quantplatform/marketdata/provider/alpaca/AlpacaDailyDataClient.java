package com.quantplatform.marketdata.provider.alpaca;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.ingestion.DailyPrice;
import com.quantplatform.marketdata.config.AlpacaProperties;
import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.operations.DailyPriceProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class AlpacaDailyDataClient {
    private final WebClient client;
    private final AlpacaProperties alpaca;
    private final MarketDataProperties market;
    private final DailyPriceProperties properties;
    private final ProviderRequestGate gate;
    private final Clock clock;
    public AlpacaDailyDataClient(WebClient.Builder builder, AlpacaProperties alpaca, MarketDataProperties market,
            DailyPriceProperties properties, ProviderRequestGate gate, Clock clock) {
        this.client = builder.clone().defaultHeader("APCA-API-KEY-ID", Objects.toString(alpaca.keyId(), ""))
                .defaultHeader("APCA-API-SECRET-KEY", Objects.toString(alpaca.secretKey(), ""))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
        this.alpaca = alpaca;
        this.market = market;
        this.properties = properties;
        this.gate = gate;
        this.clock = clock;
    }

    public Page bars(List<String> symbols, LocalDate date, String adjustment, LocalDate vintage, String token) {
        validateSymbols(symbols);
        if (!Set.of("RAW","SPLIT_DIVIDEND").contains(adjustment)) throw new IllegalArgumentException("unsupported adjustment");
        var zone = ZoneId.of("America/New_York");
        // Alpaca adjusts using its current data; "asof" only controls symbol mapping.
        if (adjustment.equals("SPLIT_DIVIDEND") && !LocalDate.now(clock.withZone(zone)).equals(vintage))
            throw new IllegalArgumentException("adjusted requests require today's retrieval vintage");
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1);
        if (end.isAfter(clock.instant().minus(properties.publicationDelay())))
            throw new IllegalArgumentException("daily session is still inside the publication delay");
        URI uri = UriComponentsBuilder.fromUri(alpaca.baseUrl()).path("/v2/stocks/bars")
                .queryParam("symbols", String.join(",", symbols)).queryParam("timeframe", "1Day")
                .queryParam("start", date.atStartOfDay(zone).toInstant()).queryParam("end", end)
                .queryParam("adjustment", adjustment.equals("RAW") ? "raw" : "split,dividend")
                .queryParam("feed", properties.feed()).queryParam("currency", "USD")
                .queryParam("asof", "-").queryParam("sort", "asc").queryParam("limit", properties.pageLimit())
                .queryParamIfPresent("page_token", Optional.ofNullable(token).filter(s -> !s.isBlank())).build().encode().toUri();
        String raw = get(uri);
        if (adjustment.equals("SPLIT_DIVIDEND") && !LocalDate.now(clock.withZone(zone)).equals(vintage))
            throw new IllegalArgumentException("adjustment retrieval crossed the vintage boundary");
        var body = CanonicalJson.readObject(raw);
        if (!(body.get("bars") instanceof Map<?, ?> data)) throw new IllegalArgumentException("bars map is missing");
        var observations = new TreeMap<String,List<Map<String,Object>>>();
        data.forEach((symbol, rows) -> {
            if (!symbols.contains(symbol.toString()) || !(rows instanceof List<?> list))
                throw new IllegalArgumentException("unrequested symbol or invalid bars list");
            var values = new ArrayList<Map<String,Object>>();
            for (var row : (List<?>) rows) {
                var value = CanonicalJson.readObject(CanonicalJson.write(row));
                var price = new DailyPrice(Instant.parse(required(value,"t")), date, "USD", properties.feed(), vintage,
                        decimal(value,"o"), decimal(value,"h"), decimal(value,"l"), decimal(value,"c"),
                        integer(value,"v"), decimal(value,"vw"), integer(value,"n"));
                price.validateAdjustment(adjustment);
                values.add(CanonicalJson.readObject(CanonicalJson.MAPPER.writeValueAsString(price)));
            }
            observations.put(symbol.toString(), values);
        });
        return new Page(observations, Objects.toString(body.get("next_page_token"), ""), raw, uri.toString(), clock.instant());
    }

    public Page actions(List<String> symbols, LocalDate date, String token) {
        validateSymbols(symbols);
        URI uri = UriComponentsBuilder.fromUri(alpaca.baseUrl()).path("/v1/corporate-actions")
                .queryParam("symbols", String.join(",", symbols)).queryParam("start", date).queryParam("end", date)
                .queryParam("limit", Math.min(properties.pageLimit(), 1000)).queryParam("sort", "asc")
                .queryParam("data_quality", "all")
                .queryParamIfPresent("page_token", Optional.ofNullable(token).filter(s -> !s.isBlank())).build().encode().toUri();
        String raw = get(uri);
        var body = CanonicalJson.readObject(raw);
        if (!(body.get("corporate_actions") instanceof Map<?, ?> groups)) throw new IllegalArgumentException("corporate actions map is missing");
        var result = new TreeMap<String,List<Map<String,Object>>>();
        symbols.forEach(symbol -> result.put(symbol, new ArrayList<>()));
        groups.forEach((type, rows) -> {
            if (!(rows instanceof List<?> list)) throw new IllegalArgumentException("invalid corporate actions list");
            for (var row : (List<?>) rows) {
                var action = new TreeMap<>(CanonicalJson.readObject(CanonicalJson.write(row)));
                action.put("type", type.toString());
                String subject = subject(action);
                if (!LocalDate.parse(required(action,"process_date")).equals(date))
                    throw new IllegalArgumentException("action process date is outside the request");
                // A provider may return both counterparties. Only the affected security owns the action.
                if (result.containsKey(subject)) result.get(subject).add(action);
            }
        });
        return new Page(result, Objects.toString(body.get("next_page_token"), ""), raw, uri.toString(), clock.instant());
    }

    public static String subject(Map<String,Object> action) {
        for (String key : List.of("symbol","old_symbol","source_symbol","acquiree_symbol"))
            if (action.get(key) != null) return action.get(key).toString();
        throw new IllegalArgumentException("action subject is missing");
    }

    private String get(URI uri) {
        gate.awaitTurn();
        return client.get().uri(uri).exchangeToMono(response -> {
            if (response.statusCode().value() == 429) {
                Duration delay = retryDelay(response.headers().asHttpHeaders().getFirst("Retry-After"),
                        response.headers().asHttpHeaders().getFirst("X-RateLimit-Reset"));
                gate.postpone(delay);
                return response.releaseBody().then(reactor.core.publisher.Mono.error(new ProviderRequestGate.DeferredRequest(delay)));
            }
            if (response.statusCode().isError()) return response.createException().flatMap(reactor.core.publisher.Mono::error);
            return response.bodyToMono(String.class);
        }).block(market.providerTimeout());
    }

    private Duration retryDelay(String retry, String reset) {
        long seconds = 60;
        try {
            if (retry != null) seconds = Long.parseLong(retry);
            else if (reset != null) seconds = Long.parseLong(reset) - clock.instant().getEpochSecond();
        } catch (RuntimeException ignored) {
            try { seconds = Duration.between(clock.instant(), ZonedDateTime.parse(retry,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds(); }
            catch (RuntimeException invalid) { seconds = 60; }
        }
        return Duration.ofSeconds(Math.max(1, Math.min(seconds, 3600)));
    }
    private void validateSymbols(List<String> symbols) {
        if (symbols.isEmpty() || symbols.size() > properties.symbolsPerRequest()
                || symbols.stream().anyMatch(s -> !s.matches("[A-Z0-9./-]{1,32}")) || new HashSet<>(symbols).size() != symbols.size())
            throw new IllegalArgumentException("invalid bounded symbol batch");
    }
    private static String required(Map<String,Object> data, String key) {
        return Objects.requireNonNull(data.get(key), "missing " + key).toString();
    }
    private static BigDecimal decimal(Map<String,Object> data, String key) {
        return data.get(key) == null ? null : new BigDecimal(data.get(key).toString());
    }
    private static long integer(Map<String,Object> data, String key) {
        return new BigDecimal(required(data,key)).longValueExact();
    }
    public record Page(Map<String,List<Map<String,Object>>> observations, String nextPageToken,
            String rawJson, String sourceUri, Instant retrievedAt) { }
}
