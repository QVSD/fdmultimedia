package com.fdmultimedia.api.analytics;

public class AnalyticsCollectionException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public AnalyticsCollectionException(String code, String safeMessage, boolean retryable) {
        super(safeMessage);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
