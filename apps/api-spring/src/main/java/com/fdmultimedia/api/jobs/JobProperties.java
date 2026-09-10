package com.fdmultimedia.api.jobs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.jobs")
public class JobProperties {

    private Duration leaseDuration = Duration.ofSeconds(30);
    private int defaultMaxAttempts = 3;
    private long maxSystemTestDurationMs = 10_000;
    private int maxSystemTestMessageLength = 200;

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    public long getMaxSystemTestDurationMs() {
        return maxSystemTestDurationMs;
    }

    public void setMaxSystemTestDurationMs(long maxSystemTestDurationMs) {
        this.maxSystemTestDurationMs = maxSystemTestDurationMs;
    }

    public int getMaxSystemTestMessageLength() {
        return maxSystemTestMessageLength;
    }

    public void setMaxSystemTestMessageLength(int maxSystemTestMessageLength) {
        this.maxSystemTestMessageLength = maxSystemTestMessageLength;
    }
}
