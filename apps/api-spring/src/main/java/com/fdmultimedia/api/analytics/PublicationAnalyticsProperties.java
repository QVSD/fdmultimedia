package com.fdmultimedia.api.analytics;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.publication-analytics")
public class PublicationAnalyticsProperties {
    private boolean enabled = true;
    private int batchSize = 20;
    private Duration claimLease = Duration.ofMinutes(2);
    private Duration manualRefreshMinimum = Duration.ofMinutes(5);
    private List<Long> cadenceMinutes = List.of(15L, 60L, 360L, 1440L, 4320L, 10080L);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = Math.clamp(batchSize, 1, 100); }
    public Duration getClaimLease() { return claimLease; }
    public void setClaimLease(Duration claimLease) {
        if (claimLease == null || claimLease.compareTo(Duration.ofSeconds(10)) < 0) {
            throw new IllegalArgumentException("Analytics claim lease must be at least 10 seconds");
        }
        this.claimLease = claimLease;
    }
    public Duration getManualRefreshMinimum() { return manualRefreshMinimum; }
    public void setManualRefreshMinimum(Duration value) {
        if (value == null || value.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Analytics refresh minimum must be positive");
        }
        this.manualRefreshMinimum = value;
    }
    public List<Long> getCadenceMinutes() { return cadenceMinutes; }
    public void setCadenceMinutes(List<Long> values) {
        if (values == null || values.size() != 6 || values.stream().anyMatch(v -> v == null || v < 1)) {
            throw new IllegalArgumentException("Analytics cadence requires six positive minute values");
        }
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) <= values.get(i - 1)) {
                throw new IllegalArgumentException("Analytics cadence must be strictly increasing");
            }
        }
        this.cadenceMinutes = List.copyOf(values);
    }
}
