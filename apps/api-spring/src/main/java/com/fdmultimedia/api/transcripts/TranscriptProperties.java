package com.fdmultimedia.api.transcripts;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.transcription")
public class TranscriptProperties {

    private String provider = "LOCAL_WHISPER_CLI";
    private String model = "local";
    private long maxMediaDurationMs = 30 * 60 * 1000L;
    private int maxSegments = 10_000;
    private int maxSegmentTextLength = 4_000;
    private int maxTotalTextLength = 1_000_000;
    private long timestampToleranceMs = 1_000L;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public long getMaxMediaDurationMs() { return maxMediaDurationMs; }
    public void setMaxMediaDurationMs(long maxMediaDurationMs) { this.maxMediaDurationMs = maxMediaDurationMs; }
    public int getMaxSegments() { return maxSegments; }
    public void setMaxSegments(int maxSegments) { this.maxSegments = maxSegments; }
    public int getMaxSegmentTextLength() { return maxSegmentTextLength; }
    public void setMaxSegmentTextLength(int maxSegmentTextLength) { this.maxSegmentTextLength = maxSegmentTextLength; }
    public int getMaxTotalTextLength() { return maxTotalTextLength; }
    public void setMaxTotalTextLength(int maxTotalTextLength) { this.maxTotalTextLength = maxTotalTextLength; }
    public long getTimestampToleranceMs() { return timestampToleranceMs; }
    public void setTimestampToleranceMs(long timestampToleranceMs) { this.timestampToleranceMs = timestampToleranceMs; }
}
