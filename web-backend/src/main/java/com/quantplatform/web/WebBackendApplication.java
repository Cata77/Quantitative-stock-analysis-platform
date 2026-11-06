package com.quantplatform.web;

import com.quantplatform.auth.config.JwtProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Single HTTP backend (P2s). Auth, portfolio and screener keep their packages and contracts;
 * one process validates the access token once, without a gateway hop or service-to-service calls.
 */
@SpringBootApplication(scanBasePackages = {"com.quantplatform.web", "com.quantplatform.auth",
        "com.quantplatform.portfolio", "com.quantplatform.screener"})
@EnableConfigurationProperties({JwtProperties.class, WebProperties.class})
@EntityScan({"com.quantplatform.auth.user", "com.quantplatform.portfolio.holding"})
@EnableJpaRepositories({"com.quantplatform.auth.user", "com.quantplatform.portfolio.holding"})
public class WebBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebBackendApplication.class, args);
    }

    @Bean
    Clock webClock() {
        return Clock.systemUTC();
    }
}
