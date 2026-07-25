package com.quantplatform.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

class AuthenticatedUserHeaderFilterTest {

    @Test
    void replacesSpoofedHeaderWithJwtSubject() {
        var jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "HS256"),
                Map.of("sub", "user-42"));
        var anonymousExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/portfolio")
                .header(AuthenticatedUserHeaderFilter.USER_ID_HEADER, "attacker")
                .build());
        var exchange = anonymousExchange.mutate()
                .principal(Mono.just(new JwtAuthenticationToken(jwt)))
                .build();
        var forwardedHeader = new AtomicReference<String>();

        new AuthenticatedUserHeaderFilter().filter(exchange, forwarded -> {
            forwardedHeader.set(forwarded.getRequest().getHeaders()
                    .getFirst(AuthenticatedUserHeaderFilter.USER_ID_HEADER));
            return Mono.empty();
        }).block();

        assertThat(forwardedHeader).hasValue("user-42");
    }

    @Test
    void stripsSpoofedHeaderFromAnonymousRequest() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/auth/login")
                .header(AuthenticatedUserHeaderFilter.USER_ID_HEADER, "attacker")
                .build());
        var forwardedHeader = new AtomicReference<String>();

        new AuthenticatedUserHeaderFilter().filter(exchange, forwarded -> {
            forwardedHeader.set(forwarded.getRequest().getHeaders()
                    .getFirst(AuthenticatedUserHeaderFilter.USER_ID_HEADER));
            return Mono.empty();
        }).block();

        assertThat(forwardedHeader.get()).isNull();
    }
}
