package com.quantplatform.observability;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

class ProviderTelemetryFilterTest {
    @Test void countsQuotaAndTransportFailuresWithoutCredentialOrUrlLabels() {
        var registry=new SimpleMeterRegistry();var filter=new ProviderTelemetryFilter(registry);
        var request=ClientRequest.create(HttpMethod.GET,URI.create("https://data.alpaca.markets/path?apikey=do-not-emit"))
            .header("Authorization","secret").build();
        filter.filter(request,r->Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).build())).block();
        assertThatThrownBy(()->filter.filter(request,r->Mono.error(new IllegalStateException("secret"))).block()).isInstanceOf(IllegalStateException.class);
        assertThat(registry.get("quant.provider.requests").tags("provider","alpaca","outcome","quota").timer().count()).isEqualTo(1);
        assertThat(registry.get("quant.provider.requests").tags("outcome","transport_error").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters().toString()).doesNotContain("do-not-emit","secret","/path");
    }
}
