package com.quantplatform.marketdata;

import com.quantplatform.marketdata.canonical.CanonicalConfiguration;
import com.quantplatform.marketdata.config.AlpacaProperties;
import com.quantplatform.marketdata.config.AlphaVantageProperties;
import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.reference.ReferenceDataImportProperties;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        MarketDataProperties.class,
        AlpacaProperties.class,
        AlphaVantageProperties.class,
        com.quantplatform.marketdata.operations.IngestionProperties.class,
        com.quantplatform.marketdata.operations.DailyPriceProperties.class,
        com.quantplatform.marketdata.fundamentals.FundamentalProperties.class,
        ReferenceDataImportProperties.class
})
public class MarketDataProducerApplication {

    public static void main(String[] args) {
        var app = new SpringApplication(MarketDataProducerApplication.class);
        // A dead-letter command runs once: no collection and no background delivery meanwhile.
        app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
            var environment = event.getEnvironment();
            if (!environment.getProperty("canonical.command.operation", "serve").equals("serve")) {
                environment.getPropertySources().addFirst(new MapPropertySource("canonical-command", Map.of(
                        "market-data.enabled", false, "canonical.delivery-enabled", false)));
            }
        });
        var context = app.run(args);
        var command = context.getBean(CanonicalConfiguration.DeadLetterCommand.class);
        if (!context.getEnvironment().getProperty("canonical.command.operation", "serve").equals("serve")) {
            int code = command.exitCode();
            System.exit(SpringApplication.exit(context, () -> code));
        }
        var properties = context.getBean(com.quantplatform.marketdata.operations.IngestionProperties.class);
        if (!properties.mode().equals("catch-up-and-serve")) {
            var runtime = context.getBeanProvider(com.quantplatform.marketdata.operations.IngestionRuntime.class).getIfAvailable();
            int code = runtime == null ? 2 : runtime.exitCode();
            System.exit(SpringApplication.exit(context, () -> code));
        }
    }
}
