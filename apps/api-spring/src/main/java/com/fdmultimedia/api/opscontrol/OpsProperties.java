package com.fdmultimedia.api.opscontrol;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bounds and thresholds of the operations control plane. Defaults were chosen against the actual workload: Jobs are claimed by
 * Workers polling every few seconds, so a queued Job older than five minutes means no Worker is keeping up.
 */
@Component
@ConfigurationProperties(prefix = "app.operations")
public class OpsProperties {
    /** Strict wall-clock bound of one optional dependency probe; a slower probe is reported UNAVAILABLE (TIMEOUT). */
    private Duration probeTimeout = Duration.ofMillis(750);
    /** How long a probe result (including a timeout) is reused; keeps polling cheap and prevents probe pile-ups. */
    private Duration probeCacheTtl = Duration.ofSeconds(10);
    private Duration backlogWarning = Duration.ofMinutes(5);
    private Duration backlogCritical = Duration.ofMinutes(30);
    private Duration overdueWarning = Duration.ofMinutes(5);
    private Duration overdueCritical = Duration.ofMinutes(30);
    private Duration recentWindow = Duration.ofHours(24);
    private int failureBurst = 3;
    /** Failures inside this window count toward a failure burst. */
    private Duration failureBurstWindow = Duration.ofHours(1);
    /** A scheduler is stale when it has not succeeded within this multiple of its configured cadence... */
    private int staleCadenceMultiplier = 3;
    /** ...and never earlier than this floor, so a fast scheduler is not flagged for one slow tick. */
    private Duration staleFloor = Duration.ofMinutes(2);
    private Duration incidentResolvedRetention = Duration.ofDays(30);
    private Duration schedulerStatusRetention = Duration.ofDays(7);
    private Duration offlineWorkerIncidentWindow = Duration.ofHours(24);

    public Duration getProbeTimeout() { return probeTimeout; }
    public void setProbeTimeout(Duration v) { this.probeTimeout = bounded(v, Duration.ofMillis(100), Duration.ofSeconds(5)); }
    public Duration getProbeCacheTtl() { return probeCacheTtl; }
    public void setProbeCacheTtl(Duration v) { this.probeCacheTtl = bounded(v, Duration.ofSeconds(1), Duration.ofMinutes(5)); }
    public Duration getBacklogWarning() { return backlogWarning; }
    public void setBacklogWarning(Duration v) { this.backlogWarning = bounded(v, Duration.ofSeconds(10), Duration.ofDays(1)); }
    public Duration getBacklogCritical() { return backlogCritical; }
    public void setBacklogCritical(Duration v) { this.backlogCritical = bounded(v, Duration.ofSeconds(10), Duration.ofDays(7)); }
    public Duration getOverdueWarning() { return overdueWarning; }
    public void setOverdueWarning(Duration v) { this.overdueWarning = bounded(v, Duration.ofSeconds(10), Duration.ofDays(1)); }
    public Duration getOverdueCritical() { return overdueCritical; }
    public void setOverdueCritical(Duration v) { this.overdueCritical = bounded(v, Duration.ofSeconds(10), Duration.ofDays(7)); }
    public Duration getRecentWindow() { return recentWindow; }
    public void setRecentWindow(Duration v) { this.recentWindow = bounded(v, Duration.ofMinutes(5), Duration.ofDays(30)); }
    public int getFailureBurst() { return failureBurst; }
    public void setFailureBurst(int v) { this.failureBurst = Math.max(1, Math.min(v, 1000)); }
    public Duration getFailureBurstWindow() { return failureBurstWindow; }
    public void setFailureBurstWindow(Duration v) { this.failureBurstWindow = bounded(v, Duration.ofMinutes(1), Duration.ofDays(7)); }
    public int getStaleCadenceMultiplier() { return staleCadenceMultiplier; }
    public void setStaleCadenceMultiplier(int v) { this.staleCadenceMultiplier = Math.max(2, Math.min(v, 100)); }
    public Duration getStaleFloor() { return staleFloor; }
    public void setStaleFloor(Duration v) { this.staleFloor = bounded(v, Duration.ofSeconds(10), Duration.ofHours(1)); }
    public Duration getIncidentResolvedRetention() { return incidentResolvedRetention; }
    public void setIncidentResolvedRetention(Duration v) { this.incidentResolvedRetention = bounded(v, Duration.ofDays(1), Duration.ofDays(365)); }
    public Duration getSchedulerStatusRetention() { return schedulerStatusRetention; }
    public void setSchedulerStatusRetention(Duration v) { this.schedulerStatusRetention = bounded(v, Duration.ofHours(1), Duration.ofDays(30)); }
    public Duration getOfflineWorkerIncidentWindow() { return offlineWorkerIncidentWindow; }
    public void setOfflineWorkerIncidentWindow(Duration v) { this.offlineWorkerIncidentWindow = bounded(v, Duration.ofMinutes(1), Duration.ofDays(30)); }

    private static Duration bounded(Duration value, Duration min, Duration max) {
        if (value == null) throw new IllegalArgumentException("duration is required");
        return value.compareTo(min) < 0 ? min : value.compareTo(max) > 0 ? max : value;
    }
}
