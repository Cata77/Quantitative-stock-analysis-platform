package com.quantplatform.auth.api;

import com.quantplatform.auth.security.JwtService;
import java.time.Instant;

public record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {

    static TokenResponse from(JwtService.IssuedToken token) {
        return new TokenResponse(token.value(), "Bearer", token.expiresAt());
    }
}
