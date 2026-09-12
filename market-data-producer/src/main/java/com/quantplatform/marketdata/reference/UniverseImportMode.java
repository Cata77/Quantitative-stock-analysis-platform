package com.quantplatform.marketdata.reference;

public enum UniverseImportMode {
    CURRENT_SNAPSHOT_FORWARD(null),
    CURRENT_CONSTITUENTS_BACKTEST("SURVIVORSHIP_BIASED"),
    ETF_HOLDINGS_PROXY("ETF_HOLDINGS_PROXY");

    private final String researchBiasLabel;

    UniverseImportMode(String researchBiasLabel) {
        this.researchBiasLabel = researchBiasLabel;
    }

    public String researchBiasLabel() {
        return researchBiasLabel;
    }
}
