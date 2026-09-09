package com.fdmultimedia.api.workers;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.workers")
public class WorkerProperties {

    private Duration offlineThreshold = Duration.ofSeconds(30);
    private Duration heartbeatInterval = Duration.ofSeconds(10);

    public Duration getOfflineThreshold() {
        return offlineThreshold;
    }

    public void setOfflineThreshold(Duration offlineThreshold) {
        this.offlineThreshold = offlineThreshold;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }
}
