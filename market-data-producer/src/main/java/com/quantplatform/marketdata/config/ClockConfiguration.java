package com.quantplatform.marketdata.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** UTC clock of the collector (moved here when the Kafka configuration was removed in P2s). */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    @Bean
    Clock marketDataClock() {
        return Clock.systemUTC();
    }
}
