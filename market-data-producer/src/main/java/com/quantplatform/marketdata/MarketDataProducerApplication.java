package com.quantplatform.marketdata;

import com.quantplatform.marketdata.config.AlpacaProperties;
import com.quantplatform.marketdata.config.AlphaVantageProperties;
import com.quantplatform.marketdata.config.MarketDataProperties;
import com.quantplatform.marketdata.config.TradierProperties;
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
        TradierProperties.class
})
public class MarketDataProducerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketDataProducerApplication.class, args);
    }
}
