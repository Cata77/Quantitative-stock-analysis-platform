package com.quantplatform.marketdata.reference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "reference-data.import", name = "enabled", havingValue = "true")
public class ReferenceSnapshotImportRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReferenceSnapshotImportRunner.class);

    private final ReferenceDataImportProperties properties;
    private final DatedUniverseCsvReader csvReader;
    private final UniverseSnapshotImporter importer;

    public ReferenceSnapshotImportRunner(
            ReferenceDataImportProperties properties,
            DatedUniverseCsvReader csvReader,
            UniverseSnapshotImporter importer
    ) {
        this.properties = properties;
        this.csvReader = csvReader;
        this.importer = importer;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        var members = csvReader.read(properties.requiredFile());
        var result = importer.importSnapshot(properties.request(members));
        LOGGER.info(
                "Reference snapshot {} with {} members (checksum={}, reused={}, issuersCreated={}, instrumentsCreated={})",
                result.snapshotId(),
                result.importedMembers(),
                result.sourceChecksum(),
                result.reusedSnapshot(),
                result.issuersCreated(),
                result.instrumentsCreated());
    }
}
