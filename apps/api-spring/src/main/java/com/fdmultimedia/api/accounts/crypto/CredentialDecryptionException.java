package com.fdmultimedia.api.accounts.crypto;

/** Thrown when stored credential ciphertext cannot be authenticated/decrypted. */
public class CredentialDecryptionException extends RuntimeException {

    public CredentialDecryptionException(String message) {
        super(message);
    }

    public CredentialDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
