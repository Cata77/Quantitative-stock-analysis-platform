package com.quantplatform.gateway.filter;

import com.quantplatform.gateway.config.GatewayRateLimitProperties;
import java.net.InetSocketAddress;
import java.time.Clock;
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
    private final java.util.Map<String, Window> clients = new java.util.HashMap<>();
    private long nextCleanup;

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

    private synchronized boolean acquire(String clientId) {
        long now=clock.millis(),window=properties.window().toMillis();
        if(now>=nextCleanup || clients.size()>=properties.maxClients()) {
            clients.values().removeIf(w->now-w.startedAt()>=window);
            nextCleanup=now+window;
        }
        Window current=clients.get(clientId);
        if(current==null) {
            if(clients.size()>=properties.maxClients()) return false;
            clients.put(clientId,new Window(now,1));return true;
        }
        if(now-current.startedAt()>=window) { clients.put(clientId,new Window(now,1));return true; }
        if(current.requests()>=properties.requests()) return false;
        clients.put(clientId,new Window(current.startedAt(),current.requests()+1));return true;
    }
    synchronized int trackedClients() { return clients.size(); }

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
