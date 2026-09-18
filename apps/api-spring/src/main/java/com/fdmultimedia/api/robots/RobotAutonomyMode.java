package com.fdmultimedia.api.robots;

/**
 * DRAFT_ONLY: the Robot selects a source, prepares a ContentDraft, and stops
 * once it is READY. A human publishes or schedules it manually.
 * REVIEW_REQUIRED: the Robot prepares a Draft and a proposed publishing
 * decision (a durable {@link RobotApproval}), but never creates a real
 * PublishSchedule until a human approves it.
 * AUTO_SCHEDULE: the Robot prepares a Draft and creates a real
 * PublishSchedule automatically, according to the Robot's own configuration.
 * Backend-gated to the TEST provider only in Phase 11C — see
 * {@code RobotService} — DRAFT_ONLY/REVIEW_REQUIRED may target any connected
 * account, including Instagram, because a human stays in the loop before
 * anything is actually scheduled.
 */
public enum RobotAutonomyMode {
    DRAFT_ONLY,
    REVIEW_REQUIRED,
    AUTO_SCHEDULE
}
