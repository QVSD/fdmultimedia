/**
 * AI content enrichment (Phase 12A) — a {@code ContentSuggestion} is a
 * durable, reviewable AI-generated suggestion for a {@code ContentDraft}'s
 * hook/caption/hashtags, never an authoritative mutation. Generation runs
 * through the existing distributed Job/Worker pipeline
 * ({@code GENERATE_SOCIAL_COPY}); this package owns domain, authorization,
 * job creation, prompt construction, and completion validation, while the
 * Worker module owns provider invocation only — no provider secret or
 * vendor SDK dependency lives here. Applying a suggestion is always an
 * explicit human action through {@code ContentSuggestionService.apply};
 * nothing in {@code com.fdmultimedia.api.robots} calls into this package in
 * Phase 12A — a Robot never generates or applies AI content on its own.
 */
package com.fdmultimedia.api.contentsuggestions;
