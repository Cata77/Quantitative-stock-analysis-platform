package com.quantplatform.marketdata.reference;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UniverseSnapshotImporter {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public UniverseSnapshotImporter(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbcClient = JdbcClient.create(dataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public UniverseImportResult importSnapshot(UniverseSnapshotImportRequest request) {
        Objects.requireNonNull(request, "request");
        var checksum = UniverseSnapshotChecksum.calculate(request);
        return Objects.requireNonNull(transactionTemplate.execute(status -> importSnapshot(request, checksum)));
    }

    private UniverseImportResult importSnapshot(UniverseSnapshotImportRequest request, String checksum) {
        jdbcClient.sql("""
                        SELECT 1
                        FROM pg_advisory_xact_lock(
                            hashtextextended(:lockKey, 0)
                        )
                        """)
                .param("lockKey", request.universeCode().name() + ":" + request.effectiveDate())
                .query(Integer.class)
                .single();

        var existing = existingSnapshot(request, checksum);
        if (existing.isPresent()) {
            return new UniverseImportResult(
                    existing.get().snapshotId(),
                    checksum,
                    existing.get().memberCount(),
                    0,
                    0,
                    true);
        }

        var universeId = jdbcClient.sql("""
                        SELECT universe_id
                        FROM reference.universes
                        WHERE code = :code
                        """)
                .param("code", request.universeCode().name())
                .query(UUID.class)
                .single();

        var snapshotId = jdbcClient.sql("""
                        INSERT INTO reference.universe_snapshots (
                            universe_id,
                            effective_date,
                            observed_at,
                            source,
                            source_uri,
                            source_checksum,
                            import_mode,
                            research_bias_label,
                            completeness_status,
                            expected_member_count,
                            imported_member_count
                        )
                        VALUES (
                            :universeId,
                            :effectiveDate,
                            :observedAt,
                            :source,
                            :sourceUri,
                            :checksum,
                            :importMode,
                            :biasLabel,
                            :completeness,
                            :expectedMemberCount,
                            :importedMemberCount
                        )
                        RETURNING universe_snapshot_id
                        """)
                .param("universeId", universeId)
                .param("effectiveDate", request.effectiveDate())
                .param("observedAt", utc(request.observedAt()))
                .param("source", request.source())
                .param("sourceUri", request.sourceUri())
                .param("checksum", checksum)
                .param("importMode", request.importMode().name())
                .param("biasLabel", request.researchBiasLabel())
                .param("completeness", request.completeness().name())
                .param("expectedMemberCount", request.expectedMemberCount())
                .param("importedMemberCount", request.members().size())
                .query(UUID.class)
                .single();

        var issuersCreated = 0;
        var instrumentsCreated = 0;
        for (var member : request.members()) {
            var resolved = resolveInstrument(member, request);
            issuersCreated += resolved.issuerCreated() ? 1 : 0;
            instrumentsCreated += resolved.instrumentCreated() ? 1 : 0;
            synchronizeSymbol(resolved.instrumentId(), member, request);
            synchronizeClassification(resolved.issuerId(), member, request);
            jdbcClient.sql("""
                            INSERT INTO reference.universe_memberships (
                                universe_snapshot_id,
                                instrument_id,
                                source_symbol,
                                source_exchange_mic,
                                primary_liquid_class
                            )
                            VALUES (
                                :snapshotId,
                                :instrumentId,
                                :symbol,
                                :exchangeMic,
                                :primaryLiquidClass
                            )
                            """)
                    .param("snapshotId", snapshotId)
                    .param("instrumentId", resolved.instrumentId())
                    .param("symbol", member.symbol())
                    .param("exchangeMic", member.exchangeMic())
                    .param("primaryLiquidClass", member.primaryLiquidClass())
                    .update();
        }

        validatePrimaryLiquidClasses(snapshotId);
        return new UniverseImportResult(
                snapshotId,
                checksum,
                request.members().size(),
                issuersCreated,
                instrumentsCreated,
                false);
    }

    private Optional<SnapshotReference> existingSnapshot(
            UniverseSnapshotImportRequest request,
            String checksum
    ) {
        return jdbcClient.sql("""
                        SELECT snapshot.universe_snapshot_id, snapshot.imported_member_count
                        FROM reference.universe_snapshots snapshot
                        JOIN reference.universes universe
                          ON universe.universe_id = snapshot.universe_id
                        WHERE universe.code = :code
                          AND snapshot.effective_date = :effectiveDate
                          AND snapshot.source_checksum = :checksum
                        """)
                .param("code", request.universeCode().name())
                .param("effectiveDate", request.effectiveDate())
                .param("checksum", checksum)
                .query((resultSet, rowNumber) -> new SnapshotReference(
                        uuid(resultSet, "universe_snapshot_id"),
                        resultSet.getInt("imported_member_count")))
                .optional();
    }

    private ResolvedInstrument resolveInstrument(
            UniverseMemberInput member,
            UniverseSnapshotImportRequest request
    ) {
        if (member.figi() != null) {
            var byFigi = jdbcClient.sql("""
                            SELECT instrument.instrument_id, instrument.issuer_id, issuer.cik
                            FROM reference.instrument_identifiers identifier
                            JOIN reference.instruments instrument
                              ON instrument.instrument_id = identifier.instrument_id
                            JOIN reference.issuers issuer
                              ON issuer.issuer_id = instrument.issuer_id
                            WHERE identifier.identifier_scheme = 'FIGI'
                              AND identifier.identifier_value = :figi
                              AND identifier.effective_to IS NULL
                            """)
                    .param("figi", member.figi())
                    .query((resultSet, rowNumber) -> new ExistingInstrument(
                            uuid(resultSet, "instrument_id"),
                            uuid(resultSet, "issuer_id"),
                            resultSet.getString("cik")))
                    .optional();
            if (byFigi.isPresent()) {
                var existing = byFigi.get();
                if (member.cik() != null && existing.cik() != null && !member.cik().equals(existing.cik())) {
                    throw new IllegalArgumentException(
                            "FIGI " + member.figi() + " is already associated with a different CIK");
                }
                updateIssuer(existing.issuerId(), member);
                updateInstrument(existing.instrumentId(), member, request.effectiveDate());
                return new ResolvedInstrument(existing.instrumentId(), existing.issuerId(), false, false);
            }
        }

        var issuer = resolveIssuer(member);
        var existingInstrument = jdbcClient.sql("""
                        SELECT instrument_id, valid_from
                        FROM reference.instruments
                        WHERE issuer_id = :issuerId
                          AND security_type = :securityType
                          AND share_class = :shareClass
                          AND valid_to IS NULL
                        """)
                .param("issuerId", issuer.issuerId())
                .param("securityType", member.securityType())
                .param("shareClass", member.shareClass())
                .query((resultSet, rowNumber) -> new InstrumentLifecycle(
                        uuid(resultSet, "instrument_id"),
                        resultSet.getObject("valid_from", LocalDate.class)))
                .optional();

        UUID instrumentId;
        var instrumentCreated = existingInstrument.isEmpty();
        if (existingInstrument.isPresent()) {
            if (existingInstrument.get().validFrom().isAfter(request.effectiveDate())) {
                throw new IllegalArgumentException(
                        "Snapshot predates the known instrument lifecycle for " + member.symbol());
            }
            instrumentId = existingInstrument.get().instrumentId();
            updateInstrument(instrumentId, member, request.effectiveDate());
        } else {
            instrumentId = jdbcClient.sql("""
                            INSERT INTO reference.instruments (
                                issuer_id,
                                security_type,
                                share_class,
                                currency,
                                primary_exchange_mic,
                                valid_from
                            )
                            VALUES (
                                :issuerId,
                                :securityType,
                                :shareClass,
                                :currency,
                                :exchangeMic,
                                :validFrom
                            )
                            RETURNING instrument_id
                            """)
                    .param("issuerId", issuer.issuerId())
                    .param("securityType", member.securityType())
                    .param("shareClass", member.shareClass())
                    .param("currency", member.currency())
                    .param("exchangeMic", member.exchangeMic())
                    .param("validFrom", request.effectiveDate())
                    .query(UUID.class)
                    .single();
        }

        synchronizeFigi(instrumentId, member, request);
        return new ResolvedInstrument(
                instrumentId,
                issuer.issuerId(),
                issuer.created(),
                instrumentCreated);
    }

    private ResolvedIssuer resolveIssuer(UniverseMemberInput member) {
        Optional<UUID> existing = member.cik() == null
                ? Optional.empty()
                : jdbcClient.sql("SELECT issuer_id FROM reference.issuers WHERE cik = :cik")
                        .param("cik", member.cik())
                        .query(UUID.class)
                        .optional();
        if (existing.isPresent()) {
            updateIssuer(existing.get(), member);
            return new ResolvedIssuer(existing.get(), false);
        }

        var issuerId = jdbcClient.sql("""
                        INSERT INTO reference.issuers (cik, legal_name, domicile, sic)
                        VALUES (:cik, :legalName, :domicile, :sic)
                        RETURNING issuer_id
                        """)
                .param("cik", member.cik())
                .param("legalName", member.legalName())
                .param("domicile", member.domicile())
                .param("sic", member.sic())
                .query(UUID.class)
                .single();
        return new ResolvedIssuer(issuerId, true);
    }

    private void updateIssuer(UUID issuerId, UniverseMemberInput member) {
        jdbcClient.sql("""
                        UPDATE reference.issuers
                        SET cik = COALESCE(cik, :cik),
                            legal_name = :legalName,
                            domicile = COALESCE(:domicile, domicile),
                            sic = COALESCE(:sic, sic),
                            active = TRUE,
                            updated_at = NOW()
                        WHERE issuer_id = :issuerId
                        """)
                .param("cik", member.cik())
                .param("legalName", member.legalName())
                .param("domicile", member.domicile())
                .param("sic", member.sic())
                .param("issuerId", issuerId)
                .update();
    }

    private void updateInstrument(UUID instrumentId, UniverseMemberInput member, LocalDate effectiveDate) {
        var updated = jdbcClient.sql("""
                        UPDATE reference.instruments
                        SET currency = :currency,
                            primary_exchange_mic = :exchangeMic,
                            active = TRUE,
                            updated_at = NOW()
                        WHERE instrument_id = :instrumentId
                          AND valid_from <= :effectiveDate
                        """)
                .param("currency", member.currency())
                .param("exchangeMic", member.exchangeMic())
                .param("instrumentId", instrumentId)
                .param("effectiveDate", effectiveDate)
                .update();
        if (updated != 1) {
            throw new IllegalArgumentException(
                    "Snapshot predates the known instrument lifecycle for " + member.symbol());
        }
    }

    private void synchronizeFigi(
            UUID instrumentId,
            UniverseMemberInput member,
            UniverseSnapshotImportRequest request
    ) {
        if (member.figi() == null) {
            return;
        }
        var existingInstrument = jdbcClient.sql("""
                        SELECT instrument_id
                        FROM reference.instrument_identifiers
                        WHERE identifier_scheme = 'FIGI'
                          AND identifier_value = :figi
                          AND effective_to IS NULL
                        """)
                .param("figi", member.figi())
                .query(UUID.class)
                .optional();
        if (existingInstrument.isPresent()) {
            if (!existingInstrument.get().equals(instrumentId)) {
                throw new IllegalArgumentException("FIGI is already mapped to a different instrument: " + member.figi());
            }
            return;
        }

        jdbcClient.sql("""
                        INSERT INTO reference.instrument_identifiers (
                            instrument_id,
                            identifier_scheme,
                            identifier_value,
                            source,
                            effective_from,
                            available_at,
                            observed_at
                        )
                        VALUES (
                            :instrumentId,
                            'FIGI',
                            :figi,
                            :source,
                            :effectiveFrom,
                            :availableAt,
                            :observedAt
                        )
                        """)
                .param("instrumentId", instrumentId)
                .param("figi", member.figi())
                .param("source", request.source())
                .param("effectiveFrom", request.effectiveDate())
                .param("availableAt", utc(request.availableAt()))
                .param("observedAt", utc(request.observedAt()))
                .update();
    }

    private void synchronizeSymbol(
            UUID instrumentId,
            UniverseMemberInput member,
            UniverseSnapshotImportRequest request
    ) {
        var current = jdbcClient.sql("""
                        SELECT instrument_symbol_id, symbol, exchange_mic, effective_from
                        FROM reference.instrument_symbols
                        WHERE instrument_id = :instrumentId
                          AND effective_to IS NULL
                        """)
                .param("instrumentId", instrumentId)
                .query((resultSet, rowNumber) -> new CurrentSymbol(
                        uuid(resultSet, "instrument_symbol_id"),
                        resultSet.getString("symbol"),
                        resultSet.getString("exchange_mic"),
                        resultSet.getObject("effective_from", LocalDate.class)))
                .optional();
        if (current.isPresent()
                && current.get().symbol().equals(member.symbol())
                && current.get().exchangeMic().equals(member.exchangeMic())) {
            return;
        }
        if (current.isPresent()) {
            if (!current.get().effectiveFrom().isBefore(request.effectiveDate())) {
                throw new IllegalArgumentException(
                        "A symbol change must have a later effective date for instrument " + instrumentId);
            }
            jdbcClient.sql("""
                            UPDATE reference.instrument_symbols
                            SET effective_to = :effectiveTo
                            WHERE instrument_symbol_id = :symbolId
                            """)
                    .param("effectiveTo", request.effectiveDate())
                    .param("symbolId", current.get().symbolId())
                    .update();
        }

        jdbcClient.sql("""
                        INSERT INTO reference.instrument_symbols (
                            instrument_id,
                            symbol,
                            exchange_mic,
                            effective_from,
                            source,
                            available_at,
                            observed_at
                        )
                        VALUES (
                            :instrumentId,
                            :symbol,
                            :exchangeMic,
                            :effectiveFrom,
                            :source,
                            :availableAt,
                            :observedAt
                        )
                        """)
                .param("instrumentId", instrumentId)
                .param("symbol", member.symbol())
                .param("exchangeMic", member.exchangeMic())
                .param("effectiveFrom", request.effectiveDate())
                .param("source", request.source())
                .param("availableAt", utc(request.availableAt()))
                .param("observedAt", utc(request.observedAt()))
                .update();
    }

    private void synchronizeClassification(
            UUID issuerId,
            UniverseMemberInput member,
            UniverseSnapshotImportRequest request
    ) {
        if (member.mappedSector() == null) {
            return;
        }
        var versionId = jdbcClient.sql("""
                        SELECT classification_version_id
                        FROM reference.classification_versions
                        WHERE source = :source
                          AND version = :version
                        """)
                .param("source", request.classificationSource())
                .param("version", request.classificationVersion())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown classification mapping version: "
                                + request.classificationSource() + "/" + request.classificationVersion()));

        var current = jdbcClient.sql("""
                        SELECT issuer_classification_id, source_classification, mapped_sector, effective_from
                        FROM reference.issuer_classifications
                        WHERE issuer_id = :issuerId
                          AND classification_version_id = :versionId
                          AND effective_to IS NULL
                        """)
                .param("issuerId", issuerId)
                .param("versionId", versionId)
                .query((resultSet, rowNumber) -> new CurrentClassification(
                        uuid(resultSet, "issuer_classification_id"),
                        resultSet.getString("source_classification"),
                        resultSet.getString("mapped_sector"),
                        resultSet.getObject("effective_from", LocalDate.class)))
                .optional();
        if (current.isPresent()
                && Objects.equals(current.get().sourceClassification(), member.sourceClassification())
                && current.get().mappedSector().equals(member.mappedSector())) {
            return;
        }
        if (current.isPresent()) {
            if (!current.get().effectiveFrom().isBefore(request.effectiveDate())) {
                throw new IllegalArgumentException(
                        "A classification change must have a later effective date for issuer " + issuerId);
            }
            jdbcClient.sql("""
                            UPDATE reference.issuer_classifications
                            SET effective_to = :effectiveTo
                            WHERE issuer_classification_id = :classificationId
                            """)
                    .param("effectiveTo", request.effectiveDate())
                    .param("classificationId", current.get().classificationId())
                    .update();
        }

        jdbcClient.sql("""
                        INSERT INTO reference.issuer_classifications (
                            issuer_id,
                            classification_version_id,
                            source_classification,
                            mapped_sector,
                            effective_from,
                            available_at,
                            observed_at
                        )
                        VALUES (
                            :issuerId,
                            :versionId,
                            :sourceClassification,
                            :mappedSector,
                            :effectiveFrom,
                            :availableAt,
                            :observedAt
                        )
                        """)
                .param("issuerId", issuerId)
                .param("versionId", versionId)
                .param("sourceClassification", member.sourceClassification())
                .param("mappedSector", member.mappedSector())
                .param("effectiveFrom", request.effectiveDate())
                .param("availableAt", utc(request.availableAt()))
                .param("observedAt", utc(request.observedAt()))
                .update();
    }

    private void validatePrimaryLiquidClasses(UUID snapshotId) {
        var invalidIssuers = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM (
                            SELECT instrument.issuer_id
                            FROM reference.universe_memberships membership
                            JOIN reference.instruments instrument
                              ON instrument.instrument_id = membership.instrument_id
                            WHERE membership.universe_snapshot_id = :snapshotId
                            GROUP BY instrument.issuer_id
                            HAVING COUNT(*) FILTER (WHERE membership.primary_liquid_class) <> 1
                        ) invalid_issuer
                        """)
                .param("snapshotId", snapshotId)
                .query(Long.class)
                .single();
        if (invalidIssuers > 0) {
            throw new IllegalArgumentException(
                    "Every issuer in a universe snapshot must have exactly one primary liquid share class");
        }
    }

    private UUID uuid(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, UUID.class);
    }

    private OffsetDateTime utc(java.time.Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record SnapshotReference(UUID snapshotId, int memberCount) {
    }

    private record ExistingInstrument(UUID instrumentId, UUID issuerId, String cik) {
    }

    private record InstrumentLifecycle(UUID instrumentId, LocalDate validFrom) {
    }

    private record ResolvedIssuer(UUID issuerId, boolean created) {
    }

    private record ResolvedInstrument(
            UUID instrumentId,
            UUID issuerId,
            boolean issuerCreated,
            boolean instrumentCreated
    ) {
    }

    private record CurrentSymbol(UUID symbolId, String symbol, String exchangeMic, LocalDate effectiveFrom) {
    }

    private record CurrentClassification(
            UUID classificationId,
            String sourceClassification,
            String mappedSector,
            LocalDate effectiveFrom
    ) {
    }
}
