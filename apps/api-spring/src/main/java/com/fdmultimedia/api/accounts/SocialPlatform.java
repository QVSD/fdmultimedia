package com.fdmultimedia.api.accounts;

/**
 * Controlled set of publishing platforms known to the domain model.
 *
 * Only {@link #TEST} has a working {@code PublishingProvider} in Phase 10A.
 * {@link #INSTAGRAM} and {@link #TIKTOK} exist so the schema and API shapes
 * do not need to change when a real provider is added, but they are
 * rejected by account/publication creation until a real provider actually
 * exists (Phase 10B+). Never advertise a platform as usable unless a
 * provider implementation is actually available.
 */
public enum SocialPlatform {
    TEST,
    INSTAGRAM,
    TIKTOK
}
