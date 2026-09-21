package com.fdmultimedia.api.robots;

/**
 * TOP_HIGHLIGHT: select the highest-ranked candidate from a completed
 * {@code DETERMINISTIC_V2} highlight analysis (see {@code HighlightService},
 * {@code DeterministicMultimodalHighlightAnalyzer} on the Worker — Phase 17A,
 * "SEMANTIC_HIGHLIGHTS_V2"). V2 is transcript-driven (deterministic
 * candidate generation, feature scoring, and non-maximum suppression over
 * already-persisted transcript segments) but has no LLM/embedding dependency,
 * so — like the original Phase 7A {@code DETERMINISTIC_V1} it supersedes
 * here — it is always available and safe for unattended automation. A source
 * asset with no completed transcript yet fails the run explicitly
 * ({@code SOURCE_UNAVAILABLE} / "TRANSCRIPT_REQUIRED") rather than silently
 * falling back to V1's older position-based heuristic.
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
