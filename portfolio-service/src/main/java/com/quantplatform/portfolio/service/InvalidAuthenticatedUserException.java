package com.quantplatform.portfolio.service;

public class InvalidAuthenticatedUserException extends RuntimeException {

    public InvalidAuthenticatedUserException() {
        super("A valid authenticated user identity is required");
    }
}
