package com.fdmultimedia.api.accounts;

/** Thrown when an OAuth callback's state parameter is missing, unknown, expired, reused, or mismatched. */
public class InvalidOAuthStateException extends RuntimeException {

    public InvalidOAuthStateException(String message) {
        super(message);
    }
}
