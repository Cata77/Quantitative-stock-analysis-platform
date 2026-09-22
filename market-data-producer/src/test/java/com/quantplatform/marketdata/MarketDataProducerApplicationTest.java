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

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Test void internalStatusRequiresAnExplicitOperationsIdentity() {
        var client=org.springframework.test.web.reactive.server.WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:"+port).build();
        var user=java.util.UUID.randomUUID();
        client.get().uri("/internal/ingestion/status").header("X-Authenticated-User-Id",user.toString())
            .exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/ingestion/status").header("Authorization",com.quantplatform.security.SecurityTokens.token(user))
            .exchange().expectStatus().isForbidden();
        client.get().uri("/internal/ingestion/status").header("Authorization",com.quantplatform.security.SecurityTokens.token(user,"operations:read"))
            .exchange().expectStatus().isOk();
    }
}
