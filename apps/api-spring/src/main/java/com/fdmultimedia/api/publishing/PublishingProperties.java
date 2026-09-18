package com.fdmultimedia.api.publishing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.publishing")
public class PublishingProperties {

    private long maxDownloadSizeBytes = 524_288_000L;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);

    public long getMaxDownloadSizeBytes() {
        return maxDownloadSizeBytes;
    }

    public void setMaxDownloadSizeBytes(long maxDownloadSizeBytes) {
        this.maxDownloadSizeBytes = maxDownloadSizeBytes;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
