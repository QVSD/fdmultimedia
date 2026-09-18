package com.fdmultimedia.api.contentsources;

/**
 * PAUSED keeps the source and its membership fully intact — it is only
 * excluded from feeding new Robot selections; existing Robot configuration,
 * RobotRun history, and membership rows are all untouched. There is no
 * destructive delete in Phase 11D.
 */
public enum ContentSourceStatus {
    ACTIVE,
    PAUSED
}
