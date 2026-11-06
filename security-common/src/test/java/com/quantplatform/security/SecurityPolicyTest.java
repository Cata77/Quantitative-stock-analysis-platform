package com.quantplatform.security;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
class SecurityPolicyTest {
    MockEnvironment application() { return new MockEnvironment().withProperty("spring.application.name","web-backend")
        .withProperty("spring.datasource.username","quant_web").withProperty("spring.datasource.password","unique-deployment-password-value"); }
    @Test void nonLocalRejectsMissingDefaultsAndAdministrativeUsers() {
        assertThatCode(()->SecurityPolicy.validate(application())).doesNotThrowAnyException();
        for(String value:new String[]{"","postgres_secure_pass","local-quant_web-development-only","change-this-development-secret-before-deploying"})
            assertThatThrownBy(()->SecurityPolicy.validate(application().withProperty("spring.datasource.password",value))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SecurityPolicy.validate(application().withProperty("spring.datasource.username","postgres"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SecurityPolicy.validate(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }
    @Test void applicationContextFailsBeforeStartingWithMissingDeploymentCredentials() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(PlatformSecurityAutoConfiguration.class)
            .withPropertyValues("spring.application.name=web-backend", "spring.datasource.username=quant_web")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .hasMessageContaining("Missing or development credential"));
    }
    @Test void localIsExplicitAndCannotBeMixedWithProduction() {
        var local=new MockEnvironment();local.setActiveProfiles("local");
        assertThatCode(()->SecurityPolicy.validate(local)).doesNotThrowAnyException();
        local.setActiveProfiles("local","production");
        assertThatThrownBy(()->SecurityPolicy.validate(local)).isInstanceOf(IllegalStateException.class);
    }
    @Test void collectorNeedsNoBrokerConfigurationAfterP2s() {
        var collector=application().withProperty("spring.application.name","market-data-producer")
            .withProperty("spring.datasource.username","quant_ingestion");
        assertThatCode(()->SecurityPolicy.validate(collector)).doesNotThrowAnyException();
    }
    @Test void webBackendRejectsWildcardAndLoopbackCorsInDeployment() {
        var web=application().withProperty("spring.application.name","web-backend").withProperty("spring.datasource.username","quant_web");
        assertThatCode(()->SecurityPolicy.validate(web)).as("no cross-origin access").doesNotThrowAnyException();
        assertThatCode(()->SecurityPolicy.validate(web.withProperty("web.allowed-origins","https://app.example"))).doesNotThrowAnyException();
        for(String origins:new String[]{"*","http://localhost:3000","http://127.0.0.1:3000"})
            assertThatThrownBy(()->SecurityPolicy.validate(application().withProperty("spring.application.name","web-backend")
                .withProperty("web.allowed-origins",origins))).isInstanceOf(IllegalStateException.class);
    }
}
