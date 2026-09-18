package com.fdmultimedia.api.publishing.instagram;

/** Normalized, safe-to-display error codes for Instagram publishing failures. */
public final class InstagramErrorCodes {

    public static final String AUTH_EXPIRED = "INSTAGRAM_AUTH_EXPIRED";
    public static final String RATE_LIMITED = "INSTAGRAM_RATE_LIMITED";
    public static final String MEDIA_REJECTED = "INSTAGRAM_MEDIA_REJECTED";
    public static final String PROCESSING_TIMEOUT = "INSTAGRAM_PROCESSING_TIMEOUT";
    public static final String TEMPORARY_ERROR = "INSTAGRAM_TEMPORARY_ERROR";
    public static final String PUBLISH_FAILED = "INSTAGRAM_PUBLISH_FAILED";
    public static final String NOT_CONFIGURED = "INSTAGRAM_NOT_CONFIGURED";
    public static final String CREDENTIAL_EXPIRED = "SOCIAL_CREDENTIAL_EXPIRED";

    private InstagramErrorCodes() {
    }
}
