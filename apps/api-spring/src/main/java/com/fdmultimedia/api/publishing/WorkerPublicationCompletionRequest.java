package com.fdmultimedia.api.publishing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Bounded provider result reported by the Worker. The backend remains
 * authoritative: this request cannot change which asset, account, or
 * workspace a Publication belongs to (those are re-derived from the Job the
 * Worker was actually assigned), and every field here is length/sanity
 * validated before being persisted.
 */
public record WorkerPublicationCompletionRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID publicationId,
        @NotNull UUID assetId,
        @NotNull UUID socialAccountId,
        @NotBlank String providerRequestId,
        @NotBlank String providerPublicationId,
        Instant publishedAt) {
}
