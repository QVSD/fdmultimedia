package com.fdmultimedia.api.jobs;

public interface OperationalJobCountsView {
    long getQueued();
    long getAssigned();
    long getRunning();
    long getSucceeded();
    long getFailed();
    long getCancelled();
}
