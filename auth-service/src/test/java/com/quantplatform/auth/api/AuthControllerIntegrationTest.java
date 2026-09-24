package com.quantplatform.auth.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quantplatform.auth.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest {

    @Autowired private com.quantplatform.security.JwtTrust trust;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clearUsers() {
        userRepository.deleteAll();
    }

    @Test
    void probesArePublicAndMetricsRequireAnOperatorToken() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health/liveness"))
            .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health/readiness"))
            .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus")
            .header("Authorization", com.quantplatform.security.SecurityTokens.token(java.util.UUID.randomUUID())))
            .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus")
            .header("Authorization", com.quantplatform.security.SecurityTokens.token(java.util.UUID.randomUUID(), "operations:read")))
            .andExpect(status().isOk());
    }

    @Test
    void registersThenAuthenticatesUser() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"Alice_01","password":"a-secure-password"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", not(blankOrNullString())))
                .andExpect(jsonPath("$.username").value("alice_01"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ALICE_01","password":"a-secure-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken", not(blankOrNullString())))
                .andExpect(jsonPath("$.expiresAt", not(blankOrNullString())));
    }

    @Test
    void issuesAsymmetricAudienceBoundTokensAndPublishesOnlyPublicKeys() throws Exception {
        var user=userRepository.save(new com.quantplatform.auth.user.User("token_user","unused-hash"));
        var properties=new com.quantplatform.auth.config.JwtProperties("classpath:security/local-private.pem","local-development-only",java.time.Duration.ofMinutes(15));
        var issued=new com.quantplatform.auth.security.JwtService(properties,trust).issue(user);
        var token=trust.decoder().decode(issued.value());
        org.assertj.core.api.Assertions.assertThat(token.getSubject()).isEqualTo(user.getId().toString());
        org.assertj.core.api.Assertions.assertThat(token.getHeaders()).containsEntry("alg","RS256").containsEntry("kid","local-development-only");
        org.assertj.core.api.Assertions.assertThat(token.getAudience()).contains("quant-platform");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/jwks"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.keys[0].n").exists()).andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void rejectsDuplicateUsername() throws Exception {
        String body = """
                {"username":"investor","password":"a-secure-password"}
                """;
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Username is already registered"));
    }

    @Test
    void rejectsInvalidCredentialsWithoutRevealingUsernameExistence() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"missing-user","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));
    }

    @Test
    void validatesRegistrationInput() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"x!","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.username").exists())
                .andExpect(jsonPath("$.violations.password").exists());
    }
}
