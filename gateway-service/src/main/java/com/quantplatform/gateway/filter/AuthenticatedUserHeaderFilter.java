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
                            headers.remove(USER_ID_HEADER);
                            headers.set(USER_ID_HEADER, authentication.getToken().getSubject());
                        }))
                        .build())
                .defaultIfEmpty(exchange.mutate()
                        .request(request -> request.headers(headers -> headers.remove(USER_ID_HEADER)))
                        .build())
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
