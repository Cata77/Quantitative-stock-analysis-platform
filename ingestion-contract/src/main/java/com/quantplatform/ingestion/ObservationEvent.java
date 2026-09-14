package com.quantplatform.ingestion;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public record ObservationEvent(UUID eventId, int schemaVersion, String observationKey, UUID datasetId,
                               UUID instrumentId, String eventType, Instant economicTime,
                               String adjustmentMode, Map<String, Object> payload) {
    public static final String HASH_HEADER = "observation-payload-sha256";

    public ObservationEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(datasetId, "datasetId");
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(economicTime, "economicTime");
        if (schemaVersion != 2 || eventType == null || eventType.isBlank()
                || adjustmentMode == null || adjustmentMode.isBlank()) {
            throw new IllegalArgumentException("unsupported or incomplete observation envelope");
        }
        payload = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(payload, "payload")));
        if (!key(datasetId, instrumentId, eventType, economicTime, adjustmentMode, payload).equals(observationKey)) {
            throw new IllegalArgumentException("observation key does not match economic payload");
        }
    }

    public static ObservationEvent create(UUID dataset, UUID instrument, String type, Instant economicTime,
                                          String adjustment, String payload) {
        var data = CanonicalJson.readObject(payload);
        return new ObservationEvent(UUID.randomUUID(), 2, key(dataset, instrument, type, economicTime, adjustment, data),
                dataset, instrument, type, economicTime, adjustment, data);
    }

    public static ObservationEvent parse(String recordKey, String json) {
        var data = CanonicalJson.readObject(json);
        var event = new ObservationEvent(UUID.fromString(data.get("eventId").toString()),
                ((Number) data.get("schemaVersion")).intValue(), data.get("observationKey").toString(),
                UUID.fromString(data.get("datasetId").toString()), UUID.fromString(data.get("instrumentId").toString()),
                data.get("eventType").toString(), Instant.parse(data.get("economicTime").toString()),
                data.get("adjustmentMode").toString(), CanonicalJson.readObject(CanonicalJson.write(data.get("payload"))));
        if (!event.instrumentId.toString().equals(recordKey)) {
            throw new IllegalArgumentException("Kafka key must be the instrument ID");
        }
        return event;
    }

    public String json() {
        return CanonicalJson.write(Map.of("eventId", eventId.toString(), "schemaVersion", schemaVersion,
                "observationKey", observationKey, "datasetId", datasetId.toString(), "instrumentId", instrumentId.toString(),
                "eventType", eventType, "economicTime", economicTime.toString(), "adjustmentMode", adjustmentMode,
                "payload", payload));
    }

    public String payloadHash() { return CanonicalJson.sha256(json()); }

    private static String key(UUID dataset, UUID instrument, String type, Instant time, String adjustment, Map<String, Object> data) {
        return CanonicalJson.hash(dataset.toString(), instrument.toString(), type, time.toString(), adjustment,
                CanonicalJson.sha256(CanonicalJson.write(data)));
    }
}
