package com.fdmultimedia.api.jobs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.scheduling")
public class WorkerSchedulingProperties {

    private String policyId = "TELEMETRY_AWARE_V1";
    private int candidateLimit = 25;
    private Duration telemetryFreshnessWindow = Duration.ofSeconds(60);
    private Duration starvationThreshold = Duration.ofMinutes(10);
    private Duration historyWindow = Duration.ofDays(7);
    private int minHistoricalSamples = 3;
    private double criticalMemoryAvailableRatio = 0.05;
    private double historyReferenceExecutionMs = 60_000;

    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public Duration getTelemetryFreshnessWindow() {
        return telemetryFreshnessWindow;
    }

    public void setTelemetryFreshnessWindow(Duration telemetryFreshnessWindow) {
        this.telemetryFreshnessWindow = telemetryFreshnessWindow;
    }

    public Duration getStarvationThreshold() {
        return starvationThreshold;
    }

    public void setStarvationThreshold(Duration starvationThreshold) {
        this.starvationThreshold = starvationThreshold;
    }

    public Duration getHistoryWindow() {
        return historyWindow;
    }

    public void setHistoryWindow(Duration historyWindow) {
        this.historyWindow = historyWindow;
    }

    public int getMinHistoricalSamples() {
        return minHistoricalSamples;
    }

    public void setMinHistoricalSamples(int minHistoricalSamples) {
        this.minHistoricalSamples = minHistoricalSamples;
    }

    public double getCriticalMemoryAvailableRatio() {
        return criticalMemoryAvailableRatio;
    }

    public void setCriticalMemoryAvailableRatio(double criticalMemoryAvailableRatio) {
        this.criticalMemoryAvailableRatio = criticalMemoryAvailableRatio;
    }

    public double getHistoryReferenceExecutionMs() {
        return historyReferenceExecutionMs;
    }

    public void setHistoryReferenceExecutionMs(double historyReferenceExecutionMs) {
        this.historyReferenceExecutionMs = historyReferenceExecutionMs;
    }

    public int safeCandidateLimit() {
        return Math.max(1, Math.min(candidateLimit, 100));
    }

    public int safeMinHistoricalSamples() {
        return Math.max(1, minHistoricalSamples);
    }

    public double safeCriticalMemoryAvailableRatio() {
        if (!Double.isFinite(criticalMemoryAvailableRatio)) {
            return 0.05;
        }
        return Math.max(0, Math.min(criticalMemoryAvailableRatio, 1));
    }

    public double safeHistoryReferenceExecutionMs() {
        if (!Double.isFinite(historyReferenceExecutionMs) || historyReferenceExecutionMs <= 0) {
            return 60_000;
        }
        return historyReferenceExecutionMs;
    }
}
