package com.quantplatform.marketdata.reference;

import java.util.UUID;

public record UniverseImportResult(
        UUID snapshotId,
        String sourceChecksum,
        int importedMembers,
        int issuersCreated,
        int instrumentsCreated,
        boolean reusedSnapshot
) {
}
