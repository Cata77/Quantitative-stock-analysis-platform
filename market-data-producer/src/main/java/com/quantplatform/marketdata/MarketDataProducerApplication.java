package com.quantplatform.marketdata;

import com.quantplatform.marketdata.config.AlpacaProperties;
import com.quantplatform.marketdata.config.AlphaVantageProperties;
import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.reference.ReferenceDataImportProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        MarketDataProperties.class,
        AlpacaProperties.class,
        AlphaVantageProperties.class,
        com.quantplatform.marketdata.operations.IngestionProperties.class,
        ReferenceDataImportProperties.class
})
public class MarketDataProducerApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(MarketDataProducerApplication.class, args);
        var properties = context.getBean(com.quantplatform.marketdata.operations.IngestionProperties.class);
        if (!properties.mode().equals("catch-up-and-serve")) {
            var runtime = context.getBeanProvider(com.quantplatform.marketdata.operations.IngestionRuntime.class).getIfAvailable();
            int code = runtime == null ? 2 : runtime.exitCode();
            System.exit(SpringApplication.exit(context, () -> code));
        }
    }
}
