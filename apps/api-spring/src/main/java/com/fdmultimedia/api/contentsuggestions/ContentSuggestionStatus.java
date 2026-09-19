package com.fdmultimedia.api.contentsuggestions;

import java.util.List;

/**
 * PENDING: created, Job not yet claimed by a Worker.
 * GENERATING: a Worker has claimed and authorized the Job; provider call in
 * flight. A lease-recovered retry re-enters GENERATING the next time a
 * Worker re-authorizes the same Job — there is no separate "queued for
 * retry" status, mirroring how HighlightAnalysis/MediaTranscript behave.
 * READY: provider output was parsed and validated; a human may Apply or
 * Discard it.
 * FAILED: terminal — either the Job exhausted its bounded retries, or the
 * provider's output was rejected as malformed/oversized (never retried,
 * since retrying the same input through the same parser will not fix bad
 * output).
 * APPLIED / DISCARDED: terminal, human-decided outcomes of a READY
 * suggestion. History is never deleted or overwritten.
 */
public enum ContentSuggestionStatus {
    PENDING,
    GENERATING,
    READY,
    FAILED,
    APPLIED,
    DISCARDED;

    private static final List<ContentSuggestionStatus> TERMINAL = List.of(FAILED, APPLIED, DISCARDED);

    public static List<ContentSuggestionStatus> terminalStatuses() {
        return TERMINAL;
    }
}
