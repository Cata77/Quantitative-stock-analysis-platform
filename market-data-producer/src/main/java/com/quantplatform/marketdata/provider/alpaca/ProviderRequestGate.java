package com.quantplatform.marketdata.provider.alpaca;

import com.quantplatform.marketdata.operations.DailyPriceProperties;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ProviderRequestGate {
    private final JdbcClient jdbc;
    private final DailyPriceProperties properties;
    public ProviderRequestGate(DataSource source, DailyPriceProperties properties) {
        jdbc = JdbcClient.create(source);
        this.properties = properties;
    }

    public void awaitTurn() {
        while (!Thread.currentThread().isInterrupted()) {
            var delay = jdbc.sql("""
                    INSERT INTO operations.provider_request_budgets (provider_code,next_request_at)
                    VALUES ('alpaca',clock_timestamp() + :spacing * INTERVAL '1 millisecond')
                    ON CONFLICT (provider_code) DO UPDATE
                    SET next_request_at = GREATEST(clock_timestamp(), operations.provider_request_budgets.next_request_at)
                        + :spacing * INTERVAL '1 millisecond'
                    RETURNING GREATEST(0, EXTRACT(EPOCH FROM (next_request_at-clock_timestamp())) * 1000 - :spacing)::bigint
                    """).param("spacing", properties.requestSpacing().toMillis()).query(Long.class).single();
            if (delay > 60_000) throw new DeferredRequest(Duration.ofMillis(delay));
            if (delay > 0) LockSupport.parkNanos(Duration.ofMillis(delay).toNanos());
            if (!Thread.currentThread().isInterrupted()) return;
        }
        throw new IllegalStateException("provider request interrupted");
    }

    public void postpone(Duration delay) {
        jdbc.sql("""
                INSERT INTO operations.provider_request_budgets VALUES ('alpaca',clock_timestamp() + :delay * INTERVAL '1 millisecond')
                ON CONFLICT (provider_code) DO UPDATE
                SET next_request_at = GREATEST(operations.provider_request_budgets.next_request_at,EXCLUDED.next_request_at)
                """).param("delay", delay.toMillis()).update();
    }
    public static class DeferredRequest extends RuntimeException {
        private final Duration delay;
        public DeferredRequest(Duration delay) { super("provider rate limit; retry later"); this.delay = delay; }
        public Duration delay() { return delay; }
    }
}
