package com.quantplatform.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrationRequest(
        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                message = "must contain only letters, numbers, dots, underscores, or hyphens")
        String username,

        @NotBlank
        @Size(min = 12, max = 72)
        String password
) {
}
