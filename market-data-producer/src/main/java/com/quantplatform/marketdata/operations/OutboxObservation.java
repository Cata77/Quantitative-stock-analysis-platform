package com.quantplatform.marketdata.operations;

import java.time.Instant;
import java.util.Objects;

/** Payload contains economic facts, excluding retrieval times, event IDs and ticker metadata. */
public record OutboxObservation(String topic, String eventType, Instant economicTime,
                                String adjustmentMode, String payloadJson) {
    public OutboxObservation {
        Objects.requireNonNull(economicTime, "economicTime");
        for (String value : new String[] {topic, eventType, adjustmentMode, payloadJson}) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("observation fields must not be blank");
            }
        }
    }
}
