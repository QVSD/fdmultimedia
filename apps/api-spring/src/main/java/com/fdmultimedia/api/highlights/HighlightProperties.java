package com.fdmultimedia.api.highlights;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fdm.highlights")
public class HighlightProperties {

    private int maxCandidates = 10;
    private long minCandidateDurationMs = Duration.ofSeconds(3).toMillis();
    private long maxCandidateDurationMs = Duration.ofSeconds(60).toMillis();
    private int maxReasonLength = 500;
    private String deterministicAnalyzerType = "DETERMINISTIC_V1";
    private String deterministicAnalyzerVersion = "1";

    public int getMaxCandidates() { return maxCandidates; }
    public void setMaxCandidates(int maxCandidates) { this.maxCandidates = maxCandidates; }
    public long getMinCandidateDurationMs() { return minCandidateDurationMs; }
    public void setMinCandidateDurationMs(long minCandidateDurationMs) { this.minCandidateDurationMs = minCandidateDurationMs; }
    public long getMaxCandidateDurationMs() { return maxCandidateDurationMs; }
    public void setMaxCandidateDurationMs(long maxCandidateDurationMs) { this.maxCandidateDurationMs = maxCandidateDurationMs; }
    public int getMaxReasonLength() { return maxReasonLength; }
    public void setMaxReasonLength(int maxReasonLength) { this.maxReasonLength = maxReasonLength; }
    public String getDeterministicAnalyzerType() { return deterministicAnalyzerType; }
    public void setDeterministicAnalyzerType(String deterministicAnalyzerType) { this.deterministicAnalyzerType = deterministicAnalyzerType; }
    public String getDeterministicAnalyzerVersion() { return deterministicAnalyzerVersion; }
    public void setDeterministicAnalyzerVersion(String deterministicAnalyzerVersion) { this.deterministicAnalyzerVersion = deterministicAnalyzerVersion; }
}
