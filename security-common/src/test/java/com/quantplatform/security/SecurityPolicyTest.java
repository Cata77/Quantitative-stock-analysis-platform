package com.quantplatform.security;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
class SecurityPolicyTest {
    MockEnvironment application() { return new MockEnvironment().withProperty("spring.application.name","portfolio-service")
        .withProperty("spring.datasource.username","quant_portfolio").withProperty("spring.datasource.password","unique-deployment-password-value"); }
    @Test void nonLocalRejectsMissingDefaultsAndAdministrativeUsers() {
        assertThatCode(()->SecurityPolicy.validate(application())).doesNotThrowAnyException();
        for(String value:new String[]{"","postgres_secure_pass","local-quant_portfolio-development-only","change-this-development-secret-before-deploying"})
            assertThatThrownBy(()->SecurityPolicy.validate(application().withProperty("spring.datasource.password",value))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SecurityPolicy.validate(application().withProperty("spring.datasource.username","postgres"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SecurityPolicy.validate(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }
    @Test void applicationContextFailsBeforeStartingWithMissingDeploymentCredentials() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(PlatformSecurityAutoConfiguration.class)
            .withPropertyValues("spring.application.name=portfolio-service", "spring.datasource.username=quant_portfolio")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .hasMessageContaining("Missing or development credential"));
    }
    @Test void localIsExplicitAndCannotBeMixedWithProduction() {
        var local=new MockEnvironment();local.setActiveProfiles("local");
        assertThatCode(()->SecurityPolicy.validate(local)).doesNotThrowAnyException();
        local.setActiveProfiles("local","production");
        assertThatThrownBy(()->SecurityPolicy.validate(local)).isInstanceOf(IllegalStateException.class);
    }
    @Test void legacyScoringIndexerCannotBypassAuthenticatedSearchInDeployment() {
        var scoring=application().withProperty("spring.application.name","scoring-service")
            .withProperty("spring.kafka.properties.security.protocol","SSL")
            .withProperty("spring.kafka.properties.ssl.keystore.location","/run/kafka/client.jks");
        assertThatCode(()->SecurityPolicy.validate(scoring)).doesNotThrowAnyException();
        scoring.withProperty("scoring.elasticsearch.enabled","true");
        assertThatThrownBy(()->SecurityPolicy.validate(scoring)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("local-only");
        scoring.setActiveProfiles("local");
        assertThatCode(()->SecurityPolicy.validate(scoring)).doesNotThrowAnyException();
    }
    @Test void nonLocalRejectsInsecureSearchKafkaAndWildcardCors() {
        var search=application().withProperty("spring.application.name","screener-service")
            .withProperty("screener.elasticsearch.url","http://localhost:9200");
        assertThatThrownBy(()->SecurityPolicy.validate(search)).isInstanceOf(IllegalStateException.class);
        search.withProperty("screener.elasticsearch.url","https://search.example");
        assertThatThrownBy(()->SecurityPolicy.validate(search)).isInstanceOf(IllegalStateException.class);
        var gateway=new MockEnvironment().withProperty("spring.application.name","gateway-service").withProperty("gateway.allowed-origins","*");
        assertThatThrownBy(()->SecurityPolicy.validate(gateway)).isInstanceOf(IllegalStateException.class);
        var kafka=application().withProperty("spring.application.name","scoring-service").withProperty("spring.kafka.properties.security.protocol","PLAINTEXT");
        assertThatThrownBy(()->SecurityPolicy.validate(kafka)).isInstanceOf(IllegalStateException.class);
    }
}
