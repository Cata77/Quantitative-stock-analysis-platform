package com.quantplatform.marketdata.reference;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("reference-data.import")
public record ReferenceDataImportProperties(
        boolean enabled,
        String file,
        String universe,
        String effectiveDate,
        String availableAt,
        String observedAt,
        String source,
        String sourceUri,
        String mode,
        String completeness,
        int expectedMemberCount,
        String classificationSource,
        String classificationVersion
) {

    public Path requiredFile() {
        return Path.of(required(file, "reference-data.import.file"));
    }

    public UniverseSnapshotImportRequest request(List<UniverseMemberInput> members) {
        var observed = Instant.parse(required(observedAt, "reference-data.import.observed-at"));
        var available = blank(availableAt) ? observed : Instant.parse(availableAt.trim());
        return new UniverseSnapshotImportRequest(
                UniverseCode.parse(universe),
                LocalDate.parse(required(effectiveDate, "reference-data.import.effective-date")),
                available,
                observed,
                required(source, "reference-data.import.source"),
                optional(sourceUri),
                UniverseImportMode.valueOf(defaultValue(mode, "CURRENT_SNAPSHOT_FORWARD")),
                SnapshotCompleteness.valueOf(defaultValue(completeness, "COMPLETE")),
                expectedMemberCount < 1 ? members.size() : expectedMemberCount,
                optional(classificationSource),
                optional(classificationVersion),
                members);
    }

    private String defaultValue(String value, String fallback) {
        return blank(value) ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private String required(String value, String property) {
        if (blank(value)) {
            throw new IllegalArgumentException(property + " must be set when reference-data importing is enabled");
        }
        return value.trim();
    }

    private String optional(String value) {
        return blank(value) ? null : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
