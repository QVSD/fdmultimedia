package com.fdmultimedia.api.robots;

public enum RobotRunOutputStatus {
    CREATED, WAITING_FOR_DRAFT, WAITING_FOR_AI, WAITING_FOR_AI_REVIEW,
    WAITING_FOR_REVIEW, SCHEDULED, SUCCEEDED, FAILED, CANCELLED;

    public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
}
