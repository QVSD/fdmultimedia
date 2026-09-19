package com.fdmultimedia.worker;

/**
 * Provider-neutral abstraction the Worker invokes with an already-built,
 * backend-supplied prompt. Implementations own HTTP/CLI invocation only —
 * never domain logic, never authoritative validation (the backend validates
 * whatever is returned before persisting it as READY).
 */
interface ContentEnrichmentProvider {
    SocialCopyResult generate(SocialCopyAuthorization authorization) throws Exception;
}
