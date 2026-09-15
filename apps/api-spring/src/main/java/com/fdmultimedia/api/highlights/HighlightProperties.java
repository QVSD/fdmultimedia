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
    private String semanticAnalyzerType = "TRANSCRIPT_SEMANTIC_V1";
    private String semanticAnalyzerVersion = "1";
    private int semanticMaxTranscriptSegments = 500;
    private int semanticMaxTranscriptCharacters = 60000;
    private long semanticMaxAssetDurationMs = Duration.ofMinutes(20).toMillis();
    private long semanticBoundarySnapToleranceMs = Duration.ofSeconds(2).toMillis();

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
    public String getSemanticAnalyzerType() { return semanticAnalyzerType; }
    public void setSemanticAnalyzerType(String semanticAnalyzerType) { this.semanticAnalyzerType = semanticAnalyzerType; }
    public String getSemanticAnalyzerVersion() { return semanticAnalyzerVersion; }
    public void setSemanticAnalyzerVersion(String semanticAnalyzerVersion) { this.semanticAnalyzerVersion = semanticAnalyzerVersion; }
    public int getSemanticMaxTranscriptSegments() { return semanticMaxTranscriptSegments; }
    public void setSemanticMaxTranscriptSegments(int semanticMaxTranscriptSegments) { this.semanticMaxTranscriptSegments = semanticMaxTranscriptSegments; }
    public int getSemanticMaxTranscriptCharacters() { return semanticMaxTranscriptCharacters; }
    public void setSemanticMaxTranscriptCharacters(int semanticMaxTranscriptCharacters) { this.semanticMaxTranscriptCharacters = semanticMaxTranscriptCharacters; }
    public long getSemanticMaxAssetDurationMs() { return semanticMaxAssetDurationMs; }
    public void setSemanticMaxAssetDurationMs(long semanticMaxAssetDurationMs) { this.semanticMaxAssetDurationMs = semanticMaxAssetDurationMs; }
    public long getSemanticBoundarySnapToleranceMs() { return semanticBoundarySnapToleranceMs; }
    public void setSemanticBoundarySnapToleranceMs(long semanticBoundarySnapToleranceMs) { this.semanticBoundarySnapToleranceMs = semanticBoundarySnapToleranceMs; }
}
