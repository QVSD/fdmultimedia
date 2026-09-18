package com.fdmultimedia.api.accounts;

/** Thrown when a provider operation needs a credential that is missing or expired. */
public class CredentialUnavailableException extends RuntimeException {

    public CredentialUnavailableException(String message) {
        super(message);
    }
}
