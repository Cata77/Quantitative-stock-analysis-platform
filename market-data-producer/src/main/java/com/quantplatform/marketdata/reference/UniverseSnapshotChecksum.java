package com.quantplatform.marketdata.reference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

final class UniverseSnapshotChecksum {

    private UniverseSnapshotChecksum() {
    }

    static String calculate(UniverseSnapshotImportRequest request) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, request.universeCode().name());
            add(digest, request.effectiveDate().toString());
            add(digest, request.source());
            add(digest, request.sourceUri());
            add(digest, request.importMode().name());
            request.members().stream()
                    .sorted(Comparator.comparing(UniverseMemberInput::symbol)
                            .thenComparing(UniverseMemberInput::exchangeMic))
                    .forEach(member -> {
                        add(digest, member.symbol());
                        add(digest, member.legalName());
                        add(digest, member.cik());
                        add(digest, member.figi());
                        add(digest, member.exchangeMic());
                        add(digest, member.securityType());
                        add(digest, member.shareClass());
                        add(digest, member.currency());
                        add(digest, member.domicile());
                        add(digest, member.sic());
                        add(digest, member.sourceClassification());
                        add(digest, member.mappedSector());
                        add(digest, Boolean.toString(member.primaryLiquidClass()));
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void add(MessageDigest digest, String value) {
        var normalized = value == null ? "" : value;
        digest.update(Integer.toString(normalized.length()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(normalized.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }
}
