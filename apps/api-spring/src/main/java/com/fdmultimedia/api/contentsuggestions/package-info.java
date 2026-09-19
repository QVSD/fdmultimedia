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
 *
 * <p>Phase 12B adds optional {@code Persona} support: a generation request
 * may reference a {@code com.fdmultimedia.api.personas.Persona}, whose
 * editorial fields are copied into an immutable
 * {@code com.fdmultimedia.api.personas.PersonaSnapshot} and stored as flat
 * columns on the {@code ContentSuggestion} itself at creation time.
 * {@code apply}/{@code toSummary} recompute the staleness fingerprint using
 * only that suggestion's own stored snapshot — never by re-reading the live
 * {@code Persona} row — so editing or archiving a Persona can never affect
 * an existing suggestion's history or apply-ability. This package depends
 * on {@code com.fdmultimedia.api.personas} for Persona resolution; see that
 * package's own package-info for the one narrow enum-reuse dependency that
 * runs the other way.
 */
package com.fdmultimedia.api.contentsuggestions;
