package com.quantplatform.screener.search;

public class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException(Throwable cause) {
        super("Company search is temporarily unavailable", cause);
    }
}
