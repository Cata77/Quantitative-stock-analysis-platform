package com.quantplatform.scoring.ingestion;

public class MarketDataValidationException extends RuntimeException {

    public MarketDataValidationException(String message) {
        super(message);
    }

    public MarketDataValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
