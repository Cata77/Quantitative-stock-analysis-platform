package com.quantplatform.marketdata.operations;

import java.time.Instant;
import java.util.Objects;

/** A small provider JSON response; larger durable-object manifests use the SQL storage_uri path. */
public record SourceArtifact(String requestKey, String sourceUri, Instant retrievedAt,
                             String parserVersion, String rawJson) {
    public SourceArtifact {
        Objects.requireNonNull(retrievedAt, "retrievedAt");
        for (String value : new String[] {requestKey, sourceUri, parserVersion, rawJson}) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("source artifact fields must not be blank");
            }
        }
    }
}
