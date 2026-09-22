package com.quantplatform.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@AutoConfiguration(beforeName={"org.springframework.boot.security.autoconfigure.servlet.SecurityAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.reactive.ReactiveSecurityAutoConfiguration"})
public class PlatformSecurityAutoConfiguration {
    @Bean static BeanFactoryPostProcessor enforceDeploymentConfiguration(Environment env) {
        return factory->SecurityPolicy.validate(env);
    }
    @Bean org.springframework.beans.factory.SmartInitializingSingleton verifyDatabaseRole(
            Environment env,org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> sources) {
        return ()->{
            if(SecurityPolicy.local(env)) return;
            var source=sources.getIfAvailable();if(source==null) return;
            try(var connection=source.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery(
                "SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR pg_has_role(current_user,'quant_migrator','MEMBER') FROM pg_roles WHERE rolname=current_user")) {
                if(!rows.next()||rows.getBoolean(1)) throw new IllegalStateException("Application database role has administrative privileges");
            } catch(java.sql.SQLException e) { throw new IllegalStateException("Unable to validate restricted database role"); }
        };
    }
    @Bean JwtTrust jwtTrust(Environment env) throws Exception { return new JwtTrust(env); }
    @Bean JwtDecoder platformJwtDecoder(JwtTrust trust) { return trust.decoder(); }
    @Bean ReactiveJwtDecoder platformReactiveJwtDecoder(JwtTrust trust) {
        return token->Mono.fromCallable(()->trust.decoder().decode(token)).subscribeOn(Schedulers.boundedElastic());
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
    static class ServletSecurity {
        @Bean @ConditionalOnMissingBean(org.springframework.security.web.SecurityFilterChain.class)
        org.springframework.security.web.SecurityFilterChain platformServletSecurity(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            return http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
                .sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/portfolio","/portfolio/**").hasAuthority("SCOPE_portfolio:write")
                    .requestMatchers("/screener/**").hasAuthority("SCOPE_research:read")
                    .requestMatchers("/internal/**").hasAuthority("SCOPE_operations:read").anyRequest().denyAll())
                .oauth2ResourceServer(o->o.jwt(j->{})).build();
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.REACTIVE)
    static class ReactiveSecurity {
        @Bean @ConditionalOnMissingBean(org.springframework.security.web.server.SecurityWebFilterChain.class)
        org.springframework.security.web.server.SecurityWebFilterChain platformReactiveSecurity(
                org.springframework.security.config.web.server.ServerHttpSecurity http) {
            return http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
                .authorizeExchange(a->a.pathMatchers("/actuator/health").permitAll()
                    .pathMatchers("/internal/**").hasAuthority("SCOPE_operations:read").anyExchange().denyAll())
                .oauth2ResourceServer(o->o.jwt(j->{})).build();
        }
    }
}
