package com.fdmultimedia.api.robots;

/**
 * MANUAL_ONLY: the Robot never starts itself; every run comes from Run Now.
 * INTERVAL: the central Robot scheduler starts a run every
 * {@code cadenceIntervalHours}, bounded to [1, 168] — deliberately not a
 * general cron expression; see {@code RobotProperties} for the bounds.
 */
public enum RobotCadenceType {
    MANUAL_ONLY,
    INTERVAL
}
