package com.fdmultimedia.api.jobs;

public enum JobStatus {
    QUEUED,
    ASSIGNED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
