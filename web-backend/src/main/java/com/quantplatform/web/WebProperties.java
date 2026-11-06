package com.quantplatform.web;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Browser origins allowed by CORS; empty means no cross-origin access. */
@ConfigurationProperties("web")
public record WebProperties(String allowedOrigins) {
    public List<String> origins() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) return List.of();
        return Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(origin -> !origin.isEmpty()).toList();
    }
}
