package com.fdmultimedia.api.jobs;

public enum JobExecutionOutcome {
    SUCCEEDED,
    RETRYABLE_FAILED,
    FAILED,
    LEASE_EXPIRED
}
