package com.quantplatform.scoring.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("scoring")
public record ScoringProperties(
        @NotBlank String inputTopic,
        @NotBlank String deadLetterTopic,
        @Min(1) int topicPartitions,
        @Min(1) int consumerConcurrency,
        @NotNull Duration retryBackoff,
        @Min(0) int retryAttempts,
        @NotNull Duration momentumLookback,
        @Min(2) int minimumUniverseSize,
        @Valid @NotNull Elasticsearch elasticsearch
) {

    public record Elasticsearch(
            boolean enabled,
            @NotBlank String baseUrl,
            @NotBlank String companyIndex
    ) {
    }
}
