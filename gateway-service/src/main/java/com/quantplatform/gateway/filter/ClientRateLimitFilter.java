package com.quantplatform.gateway.filter;

import com.quantplatform.gateway.config.GatewayRateLimitProperties;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ClientRateLimitFilter implements GlobalFilter, Ordered {

    private final GatewayRateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, Window> clients = new ConcurrentHashMap<>();

    @Autowired
    public ClientRateLimitFilter(GatewayRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ClientRateLimitFilter(GatewayRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.enabled() || acquire(clientId(exchange))) {
            return chain.filter(exchange);
        }

        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set("Retry-After",
                Long.toString(Math.max(1, properties.window().toSeconds())));
        return exchange.getResponse().setComplete();
    }

    private boolean acquire(String clientId) {
        long now = clock.millis();
        long windowMillis = properties.window().toMillis();
        AtomicReference<Boolean> allowed = new AtomicReference<>(false);

        clients.compute(clientId, (ignored, current) -> {
            if (current == null || now - current.startedAt() >= windowMillis) {
                allowed.set(true);
                return new Window(now, 1);
            }
            if (current.requests() < properties.requests()) {
                allowed.set(true);
                return new Window(current.startedAt(), current.requests() + 1);
            }
            return current;
        });
        return allowed.get();
    }

    private String clientId(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress == null ? "unknown" : remoteAddress.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private record Window(long startedAt, int requests) {
    }
}
