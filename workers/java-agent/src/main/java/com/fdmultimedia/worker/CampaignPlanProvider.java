package com.fdmultimedia.worker;

/**
 * Provider-neutral abstraction the Worker invokes with an already-built,
 * backend-supplied prompt — mirrors {@link ContentEnrichmentProvider}
 * exactly. Implementations own HTTP/CLI invocation only; the backend
 * performs all authoritative validation before persisting anything.
 */
interface CampaignPlanProvider {
    CampaignPlanResult generate(CampaignPlanAuthorization authorization) throws Exception;
}
