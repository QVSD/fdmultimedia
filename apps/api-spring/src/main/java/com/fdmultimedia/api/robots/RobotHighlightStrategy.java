package com.fdmultimedia.api.robots;

/**
 * TOP_HIGHLIGHT: select the highest-ranked candidate from a completed
 * {@code DETERMINISTIC_V1} highlight analysis.
 *
 * This is the only value in Phase 11C. The repository also has a
 * transcript-based {@code TRANSCRIPT_SEMANTIC_V1} analyzer
 * ({@code HighlightService}, {@code OllamaSemanticHighlightAnalyzer} on the
 * Worker), but it depends on a local Ollama LLM runtime that is not
 * guaranteed to be available (observed disabled in this project's own
 * Docker/Worker runtime during Phase 11B acceptance) — Robots deliberately
 * do not select it, so unattended automation never depends on an optional
 * local LLM dependency being installed and running. A future phase may add
 * a `SEMANTIC_TOP` value once that dependency is a first-class, always-on
 * part of the stack.
 */
public enum RobotHighlightStrategy {
    TOP_HIGHLIGHT
}
