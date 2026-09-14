package com.quantplatform.scoring;

import java.time.Clock;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableKafka
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class ScoringApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(ScoringApplication.class);
        app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
            var environment = event.getEnvironment();
            if (!environment.getProperty("scoring.command.operation", "serve").equals("serve")) {
                environment.getPropertySources().addFirst(new MapPropertySource("command-mode", Map.of(
                        "spring.kafka.listener.auto-startup", false,
                        "scoring.coverage-enabled", false,
                        "scoring.month-end-enabled", false)));
            }
        });
        var context = app.run(args);
        if (!context.getBean(com.quantplatform.scoring.config.ScoringCommandProperties.class).operation().equals("serve")) {
            int code = context.getBean(com.quantplatform.scoring.ingestion.ScoringCommandRunner.class).exitCode();
            System.exit(SpringApplication.exit(context, () -> code));
        }
    }

    @Bean
    Clock scoringClock() { return Clock.systemUTC(); }
}
