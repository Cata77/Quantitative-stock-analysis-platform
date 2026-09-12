package com.quantplatform.marketdata.reference;

import java.util.Locale;

public record UniverseMemberInput(
        String symbol,
        String legalName,
        String cik,
        String figi,
        String exchangeMic,
        String securityType,
        String shareClass,
        String currency,
        String domicile,
        String sic,
        String sourceClassification,
        String mappedSector,
        boolean primaryLiquidClass
) {

    public UniverseMemberInput {
        symbol = requiredUpper(symbol, "symbol");
        legalName = required(legalName, "legalName");
        cik = normalizeCik(cik);
        figi = optionalUpper(figi);
        exchangeMic = requiredUpper(exchangeMic, "exchangeMic");
        securityType = defaultUpper(securityType, "COMMON_STOCK");
        shareClass = defaultUpper(shareClass, "UNSPECIFIED");
        currency = defaultUpper(currency, "USD");
        domicile = optionalUpper(domicile);
        sic = optional(sic);
        sourceClassification = optional(sourceClassification);
        mappedSector = optional(mappedSector);

        if (cik == null && figi == null) {
            throw new IllegalArgumentException("Each member needs a CIK or FIGI for stable identity: " + symbol);
        }
        if (exchangeMic.length() != 4) {
            throw new IllegalArgumentException("exchangeMic must contain four characters: " + symbol);
        }
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must contain three characters: " + symbol);
        }
        if (domicile != null && domicile.length() != 2) {
            throw new IllegalArgumentException("domicile must contain two characters: " + symbol);
        }
        if (sic != null && !sic.matches("[0-9]{4}")) {
            throw new IllegalArgumentException("sic must contain four digits: " + symbol);
        }
    }

    private static String normalizeCik(String value) {
        var normalized = optional(value);
        if (normalized == null) {
            return null;
        }
        if (!normalized.matches("[0-9]{1,10}")) {
            throw new IllegalArgumentException("cik must contain at most ten digits");
        }
        return "0".repeat(10 - normalized.length()) + normalized;
    }

    private static String requiredUpper(String value, String field) {
        return required(value, field).toUpperCase(Locale.ROOT);
    }

    private static String defaultUpper(String value, String fallback) {
        var normalized = optionalUpper(value);
        return normalized == null ? fallback : normalized;
    }

    private static String optionalUpper(String value) {
        var normalized = optional(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String required(String value, String field) {
        var normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must be provided");
        }
        return normalized;
    }

    private static String optional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
