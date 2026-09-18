package com.fdmultimedia.api.robots;

/**
 * ACTIVE: eligible for scheduled runs and Run Now.
 * PAUSED: does not start new scheduled runs and rejects Run Now; existing
 * Jobs/Drafts/PublishSchedules from earlier runs are left alone. Resumable.
 * DISABLED: reserved for a future hard/irreversible state; not settable in
 * 11C (Pause is the only kill switch exposed), kept so the enum does not need
 * a breaking change later.
 *
 * This is deliberately not the same thing as {@link RobotRunStatus} — a
 * Robot being ACTIVE says nothing about whether it is currently executing.
 */
public enum RobotStatus {
    ACTIVE,
    PAUSED,
    DISABLED
}
