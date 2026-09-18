package com.fdmultimedia.api.jobs;

public interface SchedulingCountsView {
    long getClaims();
    long getFallbackClaims();
    long getStarvationOverrideClaims();
}
