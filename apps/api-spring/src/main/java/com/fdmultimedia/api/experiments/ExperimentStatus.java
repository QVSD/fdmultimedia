package com.fdmultimedia.api.experiments;

/**
 * DRAFT: editable, no assignments. ACTIVE: new eligible RobotRuns may be
 * assigned. PAUSED: no new assignments; existing assignments and in-flight
 * runs remain valid and may finish. COMPLETED/CANCELLED: terminal — no new
 * assignments, never reactivated, historical assignments always preserved.
 */
public enum ExperimentStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED
}
