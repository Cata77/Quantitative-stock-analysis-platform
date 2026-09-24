package com.quantplatform.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

/** Fixed labels only: never emit provider URLs, query strings, headers or credentials. */
public final class ProviderTelemetryFilter implements ExchangeFilterFunction {
    private final MeterRegistry registry;
    public ProviderTelemetryFilter(MeterRegistry registry) { this.registry=registry; }
    @Override public Mono<ClientResponse> filter(ClientRequest request,ExchangeFunction next) {
        return Mono.defer(() -> {
            var sample=Timer.start(registry);
            String host=request.url().getHost();
            String provider=host!=null && (host.equals("alpaca.markets")||host.endsWith(".alpaca.markets"))?"alpaca":
                host!=null && (host.equals("sec.gov")||host.endsWith(".sec.gov"))?"sec":
                host!=null && (host.equals("alphavantage.co")||host.endsWith(".alphavantage.co"))?"alphavantage":"other";
            return next.exchange(request).doOnNext(response -> record(sample,provider,
                response.statusCode().value()==429?"quota":response.statusCode().isError()?"error":"success"))
                .doOnError(failure -> record(sample,provider,"transport_error"));
        });
    }
    private void record(Timer.Sample sample,String provider,String outcome) {
        sample.stop(Timer.builder("quant.provider.requests").tags("provider",provider,"outcome",outcome)
            .description("Provider HTTP response latency and request count").register(registry));
    }
}
