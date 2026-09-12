package com.quantplatform.marketdata.reference;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

public record UniverseSnapshotImportRequest(
        UniverseCode universeCode,
        LocalDate effectiveDate,
        Instant availableAt,
        Instant observedAt,
        String source,
        String sourceUri,
        UniverseImportMode importMode,
        SnapshotCompleteness completeness,
        Integer expectedMemberCount,
        String classificationSource,
        String classificationVersion,
        List<UniverseMemberInput> members
) {

    public UniverseSnapshotImportRequest {
        if (universeCode == null || effectiveDate == null || availableAt == null || observedAt == null) {
            throw new IllegalArgumentException("Universe and snapshot dates must be provided");
        }
        if (availableAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("availableAt must not be later than observedAt");
        }
        source = required(source, "source");
        sourceUri = optional(sourceUri);
        if (importMode == null || completeness == null) {
            throw new IllegalArgumentException("Import mode and completeness must be provided");
        }
        members = members == null ? List.of() : List.copyOf(members);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("A universe snapshot must contain at least one member");
        }
        expectedMemberCount = expectedMemberCount == null ? members.size() : expectedMemberCount;
        if (expectedMemberCount < members.size()) {
            throw new IllegalArgumentException("expectedMemberCount cannot be smaller than the imported member count");
        }
        if (completeness == SnapshotCompleteness.COMPLETE && expectedMemberCount != members.size()) {
            throw new IllegalArgumentException("A complete snapshot must contain every expected member");
        }

        classificationSource = optional(classificationSource);
        classificationVersion = optional(classificationVersion);
        var containsClassifications = members.stream().anyMatch(member -> member.mappedSector() != null);
        if (containsClassifications && (classificationSource == null || classificationVersion == null)) {
            throw new IllegalArgumentException(
                    "Classification source and version are required when members contain mapped sectors");
        }
        if ((classificationSource == null) != (classificationVersion == null)) {
            throw new IllegalArgumentException("Classification source and version must be supplied together");
        }

        var sourceListings = new HashSet<String>();
        for (var member : members) {
            if (!sourceListings.add(member.symbol() + "@" + member.exchangeMic())) {
                throw new IllegalArgumentException(
                        "Duplicate source listing in snapshot: " + member.symbol() + "@" + member.exchangeMic());
            }
        }
    }

    public String researchBiasLabel() {
        return importMode.researchBiasLabel();
    }

    private static String required(String value, String field) {
        var normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must be provided");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
