package com.fdmultimedia.api.shared.operations;

/** Receives each instance's latest scheduler status so a logical, cross-replica view can be derived. */
public interface SchedulerStatusSink {
    void record(String scheduler, String instanceId, SchedulerOperationTracker.Snapshot snapshot);
}
