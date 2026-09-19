package com.fdmultimedia.api.robots;

import java.util.List;

/**
 * RUNNING: actively selecting a source candidate and/or creating the Draft.
 * There is no separate "waiting for highlight analysis" status — that
 * progress is tracked by which of {@code RobotRun}'s own nullable
 * provenance fields are populated ({@code highlightAnalysisId} ->
 * {@code highlightCandidateId} -> {@code contentDraftId}), reconciled the
 * same way on every pass, rather than adding more states than the workflow
 * needs.
 * WAITING_FOR_DRAFT: a ContentDraft exists; waiting for its own (Phase 11A)
 * workflow to reach READY/PUBLISHED.
 * WAITING_FOR_AI (Phase 12C): the Draft is READY, the Robot's
 * {@code aiPolicySnapshot} is not {@code NO_AI}, and exactly one automatic
 * {@code ContentSuggestion} has been created; waiting for its
 * {@code GENERATE_SOCIAL_COPY} Job to reach a terminal outcome. Never a
 * Robot-level retry loop — the Job itself owns bounded retries.
 * WAITING_FOR_AI_REVIEW (Phase 12C): the automatic suggestion is READY and
 * {@code aiPolicySnapshot == GENERATE_FOR_REVIEW}; waiting for a human to
 * Apply or Discard it through the ordinary ContentSuggestion endpoints.
 * This is the AI content review gate — deliberately distinct from
 * WAITING_FOR_REVIEW below, which remains exclusively the pre-existing
 * publishing approval gate. The two must never be conflated.
 * WAITING_FOR_REVIEW: Draft is READY (and, from Phase 12C, any AI review
 * step has already resolved) and a {@link RobotApproval} has been created;
 * waiting for a human publishing decision. Terminal from the automation's
 * own perspective — nothing here advances without a human action.
 * SUCCEEDED / FAILED / CANCELLED: terminal.
 */
public enum RobotRunStatus {
    RUNNING,
    WAITING_FOR_DRAFT,
    WAITING_FOR_AI,
    WAITING_FOR_AI_REVIEW,
    WAITING_FOR_REVIEW,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    private static final List<RobotRunStatus> TERMINAL = List.of(SUCCEEDED, FAILED, CANCELLED);

    public static List<RobotRunStatus> terminalStatuses() {
        return TERMINAL;
    }
}
