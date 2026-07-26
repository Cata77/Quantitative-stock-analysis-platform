package com.quantplatform.screener.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("screener")
public record ScreenerProperties(
        @Valid @NotNull Elasticsearch elasticsearch
) {

    public record Elasticsearch(
            @NotBlank String url,
            @NotBlank String companyIndex
    ) {
    }
}
