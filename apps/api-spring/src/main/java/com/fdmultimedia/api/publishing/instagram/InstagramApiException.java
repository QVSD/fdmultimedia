package com.fdmultimedia.api.publishing.instagram;

/**
 * A normalized Instagram/Meta Graph API failure. Never carries the raw
 * provider response body or any credential material — only a bounded, safe
 * code/message pair and whether the failure is retryable.
 */
public class InstagramApiException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public InstagramApiException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public InstagramApiException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
