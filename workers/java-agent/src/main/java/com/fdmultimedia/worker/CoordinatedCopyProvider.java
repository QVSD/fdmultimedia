package com.fdmultimedia.worker;

/**
 * Provider-neutral abstraction the Worker invokes with an already-built,
 * backend-supplied multi-output prompt — mirrors {@link ContentEnrichmentProvider}
 * and {@link CampaignPlanProvider} exactly. Implementations own HTTP/CLI
 * invocation only; the backend performs all authoritative validation
 * (exact output membership, duplicate-hook/caption-opening/hashtag checks)
 * before persisting anything.
 */
interface CoordinatedCopyProvider {
    CoordinatedCopyResult generate(CoordinatedCopyAuthorization authorization) throws Exception;
}
