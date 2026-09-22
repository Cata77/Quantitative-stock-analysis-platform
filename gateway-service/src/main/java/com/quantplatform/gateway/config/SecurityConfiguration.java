package com.quantplatform.gateway.config;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {
    @Bean SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
            .authorizeExchange(a->a.pathMatchers("/auth/register","/auth/login","/auth/jwks","/actuator/health").permitAll()
                .pathMatchers(org.springframework.http.HttpMethod.OPTIONS).permitAll()
                .pathMatchers("/portfolio","/portfolio/**").hasAuthority("SCOPE_portfolio:write")
                .pathMatchers("/screener/**").hasAuthority("SCOPE_research:read").anyExchange().denyAll())
            .oauth2ResourceServer(o->o.jwt(j->{})).build();
    }
}
