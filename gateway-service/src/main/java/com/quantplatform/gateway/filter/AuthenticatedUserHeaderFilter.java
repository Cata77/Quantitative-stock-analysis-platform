package com.quantplatform.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AuthenticatedUserHeaderFilter implements GlobalFilter, Ordered {

    static final String USER_ID_HEADER = "X-Authenticated-User-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(authentication -> exchange.mutate()
                        .request(request -> request.headers(headers -> {
                            stripUntrusted(headers);
                            headers.set(USER_ID_HEADER, authentication.getToken().getSubject());
                        }))
                        .build())
                .defaultIfEmpty(exchange.mutate()
                        .request(request -> request.headers(AuthenticatedUserHeaderFilter::stripUntrusted))
                        .build())
                .flatMap(chain::filter);
    }

    private static void stripUntrusted(org.springframework.http.HttpHeaders headers) {
        new java.util.ArrayList<>(headers.headerNames()).stream()
            .filter(name->name.toLowerCase(java.util.Locale.ROOT).startsWith("x-authenticated-")
                || name.toLowerCase(java.util.Locale.ROOT).startsWith("x-forwarded-") || name.equalsIgnoreCase("Forwarded"))
            .forEach(headers::remove);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
