package com.fdmultimedia.api.jobs;

public interface JobTypeMetricView {
    String getJobType();
    long getAttempts();
    long getSuccesses();
    long getFailures();
    Double getAverageQueueWaitMs();
    Double getAverageExecutionMs();
    Double getAverageTotalLatencyMs();
}
