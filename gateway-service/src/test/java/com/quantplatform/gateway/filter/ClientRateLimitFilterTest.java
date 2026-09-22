package com.quantplatform.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.quantplatform.gateway.config.GatewayRateLimitProperties;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class ClientRateLimitFilterTest {

    @Test
    void rejectsRequestsBeyondConfiguredLimit() {
        var properties = new GatewayRateLimitProperties(true, 2, Duration.ofMinutes(1));
        var clock = Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC);
        var filter = new ClientRateLimitFilter(properties, clock);
        var calls = new AtomicInteger();
        var chain = (org.springframework.cloud.gateway.filter.GatewayFilterChain) exchange -> {
            calls.incrementAndGet();
            return Mono.empty();
        };

        var first = exchange();
        var second = exchange();
        var third = exchange();
        filter.filter(first, chain).block();
        filter.filter(second, chain).block();
        filter.filter(third, chain).block();

        assertThat(calls).hasValue(2);
        assertThat(third.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    void bypassesLimitWhenDisabled() {
        var properties = new GatewayRateLimitProperties(false, 1, Duration.ofMinutes(1));
        var filter = new ClientRateLimitFilter(properties);
        var calls = new AtomicInteger();
        var chain = (org.springframework.cloud.gateway.filter.GatewayFilterChain) exchange -> {
            calls.incrementAndGet();
            return Mono.empty();
        };

        filter.filter(exchange(), chain).block();
        filter.filter(exchange(), chain).block();

        assertThat(calls).hasValue(2);
    }

    @Test
    void capacityIsBoundedAndExpiredEntriesAreReclaimed() {
        var instant=new java.util.concurrent.atomic.AtomicLong(1000);
        Clock clock=new Clock() {
            public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(java.time.ZoneId zone){return this;}
            public Instant instant(){return Instant.ofEpochMilli(instant.get());}
        };
        var filter=new ClientRateLimitFilter(new GatewayRateLimitProperties(true,1,Duration.ofSeconds(10),2),clock);
        org.springframework.cloud.gateway.filter.GatewayFilterChain chain=e->Mono.empty();
        for(int i=1;i<=3;i++) {
            var request=MockServerWebExchange.from(MockServerHttpRequest.get("/auth/login")
                .remoteAddress(new InetSocketAddress("192.0.2."+i,1000)).build());
            filter.filter(request,chain).block();
            if(i==3) assertThat(request.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        assertThat(filter.trackedClients()).isEqualTo(2);
        instant.addAndGet(10001);
        filter.filter(exchange(),chain).block();assertThat(filter.trackedClients()).isEqualTo(1);
    }
    @Test
    void forwardedHeadersCannotChangeTheRateLimitIdentity() {
        var filter=new ClientRateLimitFilter(new GatewayRateLimitProperties(true,1,Duration.ofMinutes(1)));
        for(int i=1;i<=2;i++) {
            var request=MockServerWebExchange.from(MockServerHttpRequest.get("/auth/login")
                .remoteAddress(new InetSocketAddress("127.0.0.1",1000)).header("X-Forwarded-For","192.0.2."+i)
                .header("Forwarded","for=192.0.2."+i).build());
            filter.filter(request,e->Mono.empty()).block();
            if(i==2) assertThat(request.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/screener/rankings")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                .build());
    }
}
