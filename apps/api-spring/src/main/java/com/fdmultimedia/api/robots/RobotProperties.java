package com.fdmultimedia.api.robots;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.robots")
public class RobotProperties {

    /** Global kill switch: false stops scheduled runs and rejects Run Now, without touching Drafts/Publications/Jobs already in flight. */
    private boolean automationEnabled = true;
    private int minCadenceIntervalHours = 1;
    private int maxCadenceIntervalHours = 168;
    private int minScheduleDelayMinutes = 1;
    private int maxScheduleDelayMinutes = 10_080;
    private int maxRunsPerDayLimit = 24;
    private int maxActiveRunsPerWorkspace = 5;
    private int dispatchBatchSize = 10;
    private int reconciliationBatchSize = 50;

    public boolean isAutomationEnabled() {
        return automationEnabled;
    }

    public void setAutomationEnabled(boolean automationEnabled) {
        this.automationEnabled = automationEnabled;
    }

    public int getMinCadenceIntervalHours() {
        return minCadenceIntervalHours;
    }

    public void setMinCadenceIntervalHours(int minCadenceIntervalHours) {
        this.minCadenceIntervalHours = minCadenceIntervalHours;
    }

    public int getMaxCadenceIntervalHours() {
        return maxCadenceIntervalHours;
    }

    public void setMaxCadenceIntervalHours(int maxCadenceIntervalHours) {
        this.maxCadenceIntervalHours = maxCadenceIntervalHours;
    }

    public int getMinScheduleDelayMinutes() {
        return minScheduleDelayMinutes;
    }

    public void setMinScheduleDelayMinutes(int minScheduleDelayMinutes) {
        this.minScheduleDelayMinutes = minScheduleDelayMinutes;
    }

    public int getMaxScheduleDelayMinutes() {
        return maxScheduleDelayMinutes;
    }

    public void setMaxScheduleDelayMinutes(int maxScheduleDelayMinutes) {
        this.maxScheduleDelayMinutes = maxScheduleDelayMinutes;
    }

    public int getMaxRunsPerDayLimit() {
        return maxRunsPerDayLimit;
    }

    public void setMaxRunsPerDayLimit(int maxRunsPerDayLimit) {
        this.maxRunsPerDayLimit = maxRunsPerDayLimit;
    }

    public int getMaxActiveRunsPerWorkspace() {
        return maxActiveRunsPerWorkspace;
    }

    public void setMaxActiveRunsPerWorkspace(int maxActiveRunsPerWorkspace) {
        this.maxActiveRunsPerWorkspace = maxActiveRunsPerWorkspace;
    }

    public int getDispatchBatchSize() {
        return dispatchBatchSize;
    }

    public void setDispatchBatchSize(int dispatchBatchSize) {
        this.dispatchBatchSize = dispatchBatchSize;
    }

    public int getReconciliationBatchSize() {
        return reconciliationBatchSize;
    }

    public void setReconciliationBatchSize(int reconciliationBatchSize) {
        this.reconciliationBatchSize = reconciliationBatchSize;
    }
}
