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
 * WAITING_FOR_REVIEW: Draft is READY and a {@link RobotApproval} has been
 * created; waiting for a human decision. Terminal from the automation's own
 * perspective — nothing here advances without a human action.
 * SUCCEEDED / FAILED / CANCELLED: terminal.
 */
public enum RobotRunStatus {
    RUNNING,
    WAITING_FOR_DRAFT,
    WAITING_FOR_REVIEW,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    private static final List<RobotRunStatus> TERMINAL = List.of(SUCCEEDED, FAILED, CANCELLED);

    public static List<RobotRunStatus> terminalStatuses() {
        return TERMINAL;
    }
}
