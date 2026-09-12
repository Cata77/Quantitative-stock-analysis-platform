package com.quantplatform.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "market-data.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:market_data_context;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
class MarketDataProducerApplicationTest {

    @Test
    void startsWithoutProviderCredentialsWhenCollectionIsDisabled() {
    }
}
