package com.quantplatform.screener.financials;

/** The publication does not exist as a complete published run, or the instrument is not in it. */
public class FinancialsNotFoundException extends RuntimeException {
    public FinancialsNotFoundException(String message) {
        super(message);
    }
}
