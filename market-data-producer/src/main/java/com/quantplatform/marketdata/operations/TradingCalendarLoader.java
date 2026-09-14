package com.quantplatform.marketdata.operations;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.marketdata.config.AlpacaProperties;
import com.quantplatform.marketdata.config.MarketDataProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class TradingCalendarLoader {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final WebClient client;
    private final MarketDataProperties market;
    private final IngestionProperties properties;
    private final Clock clock;

    public TradingCalendarLoader(DataSource source, PlatformTransactionManager manager, WebClient.Builder builder,
            AlpacaProperties alpaca, MarketDataProperties market, IngestionProperties properties, Clock clock) {
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(manager);
        client = builder.clone().baseUrl(properties.calendarBaseUrl())
                .defaultHeader("APCA-API-KEY-ID", alpaca.keyId() == null ? "" : alpaca.keyId())
                .defaultHeader("APCA-API-SECRET-KEY", alpaca.secretKey() == null ? "" : alpaca.secretKey()).build();
        this.market = market;
        this.properties = properties;
        this.clock = clock;
    }

    public void ensure(LocalDate from, LocalDate through) {
        boolean complete = jdbc.sql("""
                SELECT COUNT(*) = (:through - :from + 1) * 4 FROM reference.trading_sessions
                WHERE exchange_mic IN ('XNYS','XNAS','ARCX','BATS') AND session_date BETWEEN :from AND :through
                """).param("from", from).param("through", through).query(Boolean.class).single();
        if (complete) return;
        String raw = client.get().uri(builder -> builder.path("/v2/calendar").queryParam("start", from)
                .queryParam("end", through).build()).retrieve().bodyToMono(String.class).block(market.providerTimeout());
        List<?> rows = CanonicalJson.MAPPER.readValue(raw, List.class);
        if (rows.isEmpty() && through.toEpochDay() - from.toEpochDay() > 7) throw new IllegalStateException("calendar response is unexpectedly empty");
        var sessions = new HashMap<LocalDate, Map<?, ?>>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> data)) throw new IllegalArgumentException("invalid calendar row");
            var date = LocalDate.parse(data.get("date").toString());
            if (date.isBefore(from) || date.isAfter(through) || sessions.put(date, data) != null) {
                throw new IllegalArgumentException("calendar contains duplicate/out-of-window dates");
            }
        }
        transactions.executeWithoutResult(status -> {
            for (String exchange : List.of("XNYS", "XNAS", "ARCX", "BATS")) {
                for (LocalDate date = from; !date.isAfter(through); date = date.plusDays(1)) {
                    var session = sessions.get(date);
                    var open = session == null ? null : date.atTime(LocalTime.parse(session.get("open").toString())).atZone(NEW_YORK).toOffsetDateTime();
                    var close = session == null ? null : date.atTime(LocalTime.parse(session.get("close").toString())).atZone(NEW_YORK).toOffsetDateTime();
                    jdbc.sql("""
                            INSERT INTO reference.trading_sessions (exchange_mic, session_date, opens_at, closes_at, timezone,
                                holiday, early_close, source, available_at, observed_at)
                            VALUES (:exchange, :date, :open, :close, 'America/New_York', :holiday, :early,
                                'ALPACA_US_EQUITIES_CALENDAR', :now, :now) ON CONFLICT DO NOTHING
                            """).param("exchange", exchange).param("date", date).param("open", open).param("close", close)
                            .param("holiday", session == null).param("early", close != null && close.toLocalTime().isBefore(LocalTime.of(16, 0)))
                            .param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
                }
                jdbc.sql("""
                        INSERT INTO operations.calendar_imports (exchange_mic, from_date, through_date, source_uri, source_content, source_hash)
                        VALUES (:exchange, :from, :through, :uri, CAST(:content AS jsonb), :hash) ON CONFLICT DO NOTHING
                        """).param("exchange", exchange).param("from", from).param("through", through)
                        .param("uri", properties.calendarBaseUrl() + "/v2/calendar?start=" + from + "&end=" + through)
                        .param("content", raw).param("hash", CanonicalJson.sha256(raw)).update();
            }
        });
    }
}
