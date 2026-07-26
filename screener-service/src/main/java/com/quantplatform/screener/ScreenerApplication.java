package com.quantplatform.screener;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@ConfigurationPropertiesScan
@SpringBootApplication
public class ScreenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScreenerApplication.class, args);
    }

    @Bean
    Clock screenerClock() {
        return Clock.systemUTC();
    }
}
