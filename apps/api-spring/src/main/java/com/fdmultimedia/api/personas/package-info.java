/**
 * Reusable, workspace-scoped editorial identity (Phase 12B) — structured
 * configuration a human generating a {@code ContentSuggestion} may
 * optionally select, never a Robot, an AI provider, a raw system prompt, a
 * social account, a Worker, or a ContentDraft. A Persona only ever shapes
 * wording/style/voice/audience/formatting; it never overrides factual
 * source-context grounding, and it never mutates a ContentDraft directly —
 * only an applied {@code ContentSuggestion} does that, exactly as in Phase
 * 12A.
 *
 * <p>This package intentionally has no dependency on
 * {@code com.fdmultimedia.api.robots}: Robots do not select or reference a
 * Persona in this phase (deferred), and no dependency on
 * {@code com.fdmultimedia.api.contentdrafts} or
 * {@code com.fdmultimedia.api.jobs} — a Persona is pure, reusable
 * configuration a caller reads or snapshots, never a participant in the
 * generation Job pipeline itself.
 *
 * <p>One deliberate, narrow exception to the codebase's usual
 * one-directional package dependency convention: this package imports
 * {@code SuggestionLanguage}/{@code SuggestionTone} from
 * {@code com.fdmultimedia.api.contentsuggestions} to reuse the exact same
 * language/tone semantics rather than fork a second, parallel enum —
 * while {@code contentsuggestions} in turn depends on this package for
 * {@link com.fdmultimedia.api.personas.Persona} resolution and
 * {@link com.fdmultimedia.api.personas.PersonaSnapshot}. Kept intentionally
 * minimal (two shared enum types only) rather than relocating already-shipped
 * Phase 12A files.
 */
package com.fdmultimedia.api.personas;
