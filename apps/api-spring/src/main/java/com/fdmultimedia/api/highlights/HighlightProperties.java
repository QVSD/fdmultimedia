package com.fdmultimedia.api.highlights;

import jakarta.annotation.PostConstruct;
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

    // SEMANTIC_HIGHLIGHTS_V2 (wire name DETERMINISTIC_V2): deterministic,
    // transcript-driven, multi-signal candidate ranking. No LLM/embedding
    // provider involved, so — unlike TRANSCRIPT_SEMANTIC_V1 — it has no
    // optional-runtime gate and is always available.
    private String v2AnalyzerType = "DETERMINISTIC_V2";
    private String v2AnalyzerVersion = "2";
    private long v2MinDurationMs = Duration.ofSeconds(15).toMillis();
    private long v2PreferredMinDurationMs = Duration.ofSeconds(25).toMillis();
    private long v2PreferredMaxDurationMs = Duration.ofSeconds(45).toMillis();
    private long v2MaxDurationMs = Duration.ofSeconds(60).toMillis();
    private int v2MaxSegmentsConsidered = 6000;
    private int v2MaxCandidateStarts = 300;
    private int v2MaxCandidateWindows = 1200;
    private java.math.BigDecimal v2OverlapSuppressionThreshold = new java.math.BigDecimal("0.50");
    private java.math.BigDecimal v2SimilarityThreshold = new java.math.BigDecimal("0.60");
    private java.math.BigDecimal v2MinTranscriptCoverage = new java.math.BigDecimal("0.30");
    private java.math.BigDecimal v2WeightHook = new java.math.BigDecimal("0.22");
    private java.math.BigDecimal v2WeightCompleteness = new java.math.BigDecimal("0.20");
    private java.math.BigDecimal v2WeightInformationDensity = new java.math.BigDecimal("0.16");
    private java.math.BigDecimal v2WeightSpeechDensity = new java.math.BigDecimal("0.14");
    private java.math.BigDecimal v2WeightBoundary = new java.math.BigDecimal("0.14");
    private java.math.BigDecimal v2WeightCoverage = new java.math.BigDecimal("0.14");
    private java.math.BigDecimal v2WeightRepetitionPenalty = new java.math.BigDecimal("0.20");

    @PostConstruct
    void validate() {
        if (v2MinDurationMs <= 0 || v2PreferredMinDurationMs < v2MinDurationMs
                || v2PreferredMaxDurationMs < v2PreferredMinDurationMs || v2MaxDurationMs < v2PreferredMaxDurationMs) {
            throw new IllegalStateException("fdm.highlights v2 duration bounds must satisfy min <= preferredMin <= preferredMax <= max");
        }
        if (v2MaxDurationMs > maxCandidateDurationMs || v2MinDurationMs < minCandidateDurationMs) {
            throw new IllegalStateException("fdm.highlights v2 duration bounds must stay within the global candidate duration bounds");
        }
        if (v2MaxSegmentsConsidered <= 0 || v2MaxCandidateStarts <= 0 || v2MaxCandidateWindows <= 0) {
            throw new IllegalStateException("fdm.highlights v2 candidate bounds must be positive");
        }
        requireUnitRange(v2OverlapSuppressionThreshold, "v2OverlapSuppressionThreshold");
        requireUnitRange(v2SimilarityThreshold, "v2SimilarityThreshold");
        requireUnitRange(v2MinTranscriptCoverage, "v2MinTranscriptCoverage");
        java.math.BigDecimal[] weights = {v2WeightHook, v2WeightCompleteness, v2WeightInformationDensity,
                v2WeightSpeechDensity, v2WeightBoundary, v2WeightCoverage, v2WeightRepetitionPenalty};
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (java.math.BigDecimal weight : weights) {
            if (weight == null || weight.signum() < 0) {
                throw new IllegalStateException("fdm.highlights v2 weights must be non-negative");
            }
            total = total.add(weight);
        }
        if (total.signum() <= 0) {
            throw new IllegalStateException("fdm.highlights v2 weights must sum to a positive value");
        }
    }

    private void requireUnitRange(java.math.BigDecimal value, String name) {
        if (value == null || value.signum() < 0 || value.compareTo(java.math.BigDecimal.ONE) > 0) {
            throw new IllegalStateException("fdm.highlights " + name + " must be between 0 and 1");
        }
    }

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

    public String getV2AnalyzerType() { return v2AnalyzerType; }
    public void setV2AnalyzerType(String v2AnalyzerType) { this.v2AnalyzerType = v2AnalyzerType; }
    public String getV2AnalyzerVersion() { return v2AnalyzerVersion; }
    public void setV2AnalyzerVersion(String v2AnalyzerVersion) { this.v2AnalyzerVersion = v2AnalyzerVersion; }
    public long getV2MinDurationMs() { return v2MinDurationMs; }
    public void setV2MinDurationMs(long v2MinDurationMs) { this.v2MinDurationMs = v2MinDurationMs; }
    public long getV2PreferredMinDurationMs() { return v2PreferredMinDurationMs; }
    public void setV2PreferredMinDurationMs(long v2PreferredMinDurationMs) { this.v2PreferredMinDurationMs = v2PreferredMinDurationMs; }
    public long getV2PreferredMaxDurationMs() { return v2PreferredMaxDurationMs; }
    public void setV2PreferredMaxDurationMs(long v2PreferredMaxDurationMs) { this.v2PreferredMaxDurationMs = v2PreferredMaxDurationMs; }
    public long getV2MaxDurationMs() { return v2MaxDurationMs; }
    public void setV2MaxDurationMs(long v2MaxDurationMs) { this.v2MaxDurationMs = v2MaxDurationMs; }
    public int getV2MaxSegmentsConsidered() { return v2MaxSegmentsConsidered; }
    public void setV2MaxSegmentsConsidered(int v2MaxSegmentsConsidered) { this.v2MaxSegmentsConsidered = v2MaxSegmentsConsidered; }
    public int getV2MaxCandidateStarts() { return v2MaxCandidateStarts; }
    public void setV2MaxCandidateStarts(int v2MaxCandidateStarts) { this.v2MaxCandidateStarts = v2MaxCandidateStarts; }
    public int getV2MaxCandidateWindows() { return v2MaxCandidateWindows; }
    public void setV2MaxCandidateWindows(int v2MaxCandidateWindows) { this.v2MaxCandidateWindows = v2MaxCandidateWindows; }
    public java.math.BigDecimal getV2OverlapSuppressionThreshold() { return v2OverlapSuppressionThreshold; }
    public void setV2OverlapSuppressionThreshold(java.math.BigDecimal v2OverlapSuppressionThreshold) { this.v2OverlapSuppressionThreshold = v2OverlapSuppressionThreshold; }
    public java.math.BigDecimal getV2SimilarityThreshold() { return v2SimilarityThreshold; }
    public void setV2SimilarityThreshold(java.math.BigDecimal v2SimilarityThreshold) { this.v2SimilarityThreshold = v2SimilarityThreshold; }
    public java.math.BigDecimal getV2MinTranscriptCoverage() { return v2MinTranscriptCoverage; }
    public void setV2MinTranscriptCoverage(java.math.BigDecimal v2MinTranscriptCoverage) { this.v2MinTranscriptCoverage = v2MinTranscriptCoverage; }
    public java.math.BigDecimal getV2WeightHook() { return v2WeightHook; }
    public void setV2WeightHook(java.math.BigDecimal v2WeightHook) { this.v2WeightHook = v2WeightHook; }
    public java.math.BigDecimal getV2WeightCompleteness() { return v2WeightCompleteness; }
    public void setV2WeightCompleteness(java.math.BigDecimal v2WeightCompleteness) { this.v2WeightCompleteness = v2WeightCompleteness; }
    public java.math.BigDecimal getV2WeightInformationDensity() { return v2WeightInformationDensity; }
    public void setV2WeightInformationDensity(java.math.BigDecimal v2WeightInformationDensity) { this.v2WeightInformationDensity = v2WeightInformationDensity; }
    public java.math.BigDecimal getV2WeightSpeechDensity() { return v2WeightSpeechDensity; }
    public void setV2WeightSpeechDensity(java.math.BigDecimal v2WeightSpeechDensity) { this.v2WeightSpeechDensity = v2WeightSpeechDensity; }
    public java.math.BigDecimal getV2WeightBoundary() { return v2WeightBoundary; }
    public void setV2WeightBoundary(java.math.BigDecimal v2WeightBoundary) { this.v2WeightBoundary = v2WeightBoundary; }
    public java.math.BigDecimal getV2WeightCoverage() { return v2WeightCoverage; }
    public void setV2WeightCoverage(java.math.BigDecimal v2WeightCoverage) { this.v2WeightCoverage = v2WeightCoverage; }
    public java.math.BigDecimal getV2WeightRepetitionPenalty() { return v2WeightRepetitionPenalty; }
    public void setV2WeightRepetitionPenalty(java.math.BigDecimal v2WeightRepetitionPenalty) { this.v2WeightRepetitionPenalty = v2WeightRepetitionPenalty; }
}
