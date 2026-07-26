package com.quantplatform.portfolio.service;

public class HoldingNotFoundException extends RuntimeException {

    public HoldingNotFoundException() {
        super("Portfolio holding was not found");
    }
}
