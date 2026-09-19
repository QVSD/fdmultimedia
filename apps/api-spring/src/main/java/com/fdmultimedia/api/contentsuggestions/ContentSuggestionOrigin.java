package com.fdmultimedia.api.contentsuggestions;

/**
 * Explicit provenance (Phase 12C) — never inferred from a nullable
 * {@code robotRunId} alone. MANUAL always carries a null {@code robotRunId};
 * ROBOT always carries the id of the {@code RobotRun} that created it. See
 * {@link ContentSuggestion#forRobot} for the only path that produces ROBOT.
 */
public enum ContentSuggestionOrigin {
    MANUAL,
    ROBOT
}
