package com.quantplatform.ingestion;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObservationCompatibilityTest {
    @Test void v2IdentitySurvivesSerializationAndAdditiveEnvelopeMetadata() {
        var event=ObservationEvent.create(UUID.fromString("10000000-0000-0000-0000-000000000001"),
            UUID.fromString("20000000-0000-0000-0000-000000000001"),"DAILY_PRICE",Instant.parse("2026-01-02T21:00:00Z"),"RAW","{\"close\":100}");
        var envelope=CanonicalJson.readObject(event.json());envelope.put("traceMetadata","additive");
        var parsed=ObservationEvent.parse(event.instrumentId().toString(),CanonicalJson.write(envelope));
        assertThat(parsed).isEqualTo(event);
        envelope.put("schemaVersion",3);
        assertThatThrownBy(()->ObservationEvent.parse(event.instrumentId().toString(),CanonicalJson.write(envelope))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ObservationEvent.parse("AAPL",event.json())).isInstanceOf(IllegalArgumentException.class);
    }
}
