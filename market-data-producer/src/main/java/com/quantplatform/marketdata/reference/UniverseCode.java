package com.quantplatform.marketdata.reference;

import java.util.Locale;

public enum UniverseCode {
    SP500,
    NASDAQ100;

    public static UniverseCode parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("universe must be provided");
        }
        var normalized = value.trim()
                .toUpperCase(Locale.ROOT)
                .replace("&", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        return switch (normalized) {
            case "SP500", "SANDP500" -> SP500;
            case "NASDAQ100", "NDX" -> NASDAQ100;
            default -> throw new IllegalArgumentException("Unsupported universe: " + value);
        };
    }
}
