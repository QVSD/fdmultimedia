package com.fdmultimedia.api.publishschedules;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.publishing.schedule")
public class PublishScheduleProperties {

    private boolean enabled = true;
    private Duration minLead = Duration.ofSeconds(30);
    private int maxHorizonDays = 365;
    private int dispatchBatchSize = 50;
    private int calendarMaxDays = 90;
    private int historyMaxResults = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getMinLead() {
        return minLead;
    }

    public void setMinLead(Duration minLead) {
        this.minLead = minLead;
    }

    public int getMaxHorizonDays() {
        return maxHorizonDays;
    }

    public void setMaxHorizonDays(int maxHorizonDays) {
        this.maxHorizonDays = maxHorizonDays;
    }

    public int getDispatchBatchSize() {
        return dispatchBatchSize;
    }

    public void setDispatchBatchSize(int dispatchBatchSize) {
        this.dispatchBatchSize = dispatchBatchSize;
    }

    public int getCalendarMaxDays() {
        return calendarMaxDays;
    }

    public void setCalendarMaxDays(int calendarMaxDays) {
        this.calendarMaxDays = calendarMaxDays;
    }

    public int getHistoryMaxResults() {
        return historyMaxResults;
    }

    public void setHistoryMaxResults(int historyMaxResults) {
        this.historyMaxResults = historyMaxResults;
    }
}
