package com.fdmultimedia.api.jobs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.scheduling.observability")
public class SchedulingObservabilityProperties {
    private int decisionRetentionDays = 30;

    public int getDecisionRetentionDays() {
        return decisionRetentionDays;
    }

    public void setDecisionRetentionDays(int decisionRetentionDays) {
        this.decisionRetentionDays = decisionRetentionDays;
    }

    public int safeDecisionRetentionDays() {
        return Math.max(1, Math.min(decisionRetentionDays, 365));
    }
}
