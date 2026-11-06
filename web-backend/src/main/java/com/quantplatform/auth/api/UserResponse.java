package com.quantplatform.auth.api;

import com.quantplatform.auth.user.User;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String username, Instant createdAt) {

    static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt());
    }
}
