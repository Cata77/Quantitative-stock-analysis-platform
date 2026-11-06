package com.quantplatform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quantplatform.security.SecurityTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The access rules formerly enforced by the Gateway, now in the single backend (P2s). */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:h2.properties")
class WebBackendSecurityTest {
    @Autowired MockMvc mvc;

    @Test void anonymousSpoofedAndMalformedTokensCannotReachProtectedRoutes() throws Exception {
        mvc.perform(get("/portfolio").header("X-Authenticated-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/screener/rankings").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/jwks")).andExpect(status().isOk());
    }

    @Test void scopesSeparatePortfolioAndScreenerAccess() throws Exception {
        mvc.perform(get("/portfolio").header("Authorization", SecurityTokens.token(UUID.randomUUID(), "research:read")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/screener/search/status").header("Authorization", SecurityTokens.token(UUID.randomUUID(), "portfolio:write")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/screener/search/status").header("Authorization", SecurityTokens.token(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test void unknownAndInternalRoutesAreDenied() throws Exception {
        String token = SecurityTokens.token(UUID.randomUUID(), "portfolio:write research:read operations:read");
        mvc.perform(get("/internal/ingestion/status").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/auth/users").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test void probesArePublicButMetricsRequireOperatorScope() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", SecurityTokens.token(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").header("Authorization", SecurityTokens.token(UUID.randomUUID(), "operations:read")))
                .andExpect(status().isOk());
    }

    @Test void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mvc.perform(options("/portfolio").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(options("/portfolio").header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
