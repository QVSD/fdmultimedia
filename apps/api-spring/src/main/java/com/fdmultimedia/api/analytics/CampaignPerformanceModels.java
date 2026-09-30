package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CampaignPerformanceModels {
    private CampaignPerformanceModels() {}

    public enum EvidenceStatus { TOO_YOUNG, METRIC_UNAVAILABLE, INSUFFICIENT_SAMPLE, LOW_COVERAGE, READY }
    public enum OutputEvidenceStatus { UNPUBLISHED, TOO_YOUNG, MISSING_SNAPSHOT, OBSERVED }
    public enum Direction { HIGHER_OBSERVED, LOWER_OBSERVED, SIMILAR_OBSERVED, INSUFFICIENT_EVIDENCE }
    public enum Dimension { ROLE, COORDINATION_POLICY }
    public enum RecommendationType {
        WAIT_FOR_OBSERVATION_WINDOW,
        COLLECT_MORE_DATA,
        CHECK_ANALYTICS_COVERAGE,
        REVIEW_OUTPUT_DIFFERENCES,
        REVIEW_ROLE_DIFFERENCES,
        REVIEW_COPY_DIFFERENCES,
        CONSIDER_CONTROLLED_EXPERIMENT
    }

    public record MetricStatistics(BigDecimal total, BigDecimal average, BigDecimal median,
            BigDecimal minimum, BigDecimal maximum, long sampleCount) {}

    public record OutputEvidence(UUID id, UUID robotRunOutputId, Integer selectionOrder, Integer sourceRank,
            String outputStatus, String campaignRole, UUID highlightCandidateId,
            UUID campaignPlanId, Integer campaignPlanRevision, UUID campaignPlanItemId,
            UUID campaignCopySetId, Integer campaignCopySetRevision, UUID campaignCopyItemId,
            UUID contentSuggestionId, UUID contentDraftId, UUID publishScheduleId,
            UUID publicationId, String provider, Instant publishedAt, UUID analyticsSnapshotId,
            OutputEvidenceStatus evidenceStatus, Map<Metric, Long> metrics) {}

    public record ObservedComparison(UUID leftOutputId, Integer leftSelectionOrder,
            UUID rightOutputId, Integer rightSelectionOrder, Metric metric,
            Long leftValue, Long rightValue, Direction direction, String message) {}

    public record Recommendation(UUID id, int sequence, RecommendationType type, Metric metric,
            String comparedDimension, Map<String, Object> evidence, String message,
            List<String> limitations) {}

    public record Review(UUID id, UUID robotRunId, int revision, Window observationWindow,
            Metric primaryMetric, String engineVersion, String recommendationEngineVersion,
            EvidenceStatus evidenceStatus, String robotRunStatus, int intendedOutputCount,
            int actualOutputCount, int publishedOutputCount, int failedOutputCount,
            int eligibleByAgeCount, int analyticsPublicationCount, Instant evidenceCutoffAt,
            Instant createdAt, Map<Metric, MetricStatistics> metrics,
            List<OutputEvidence> outputs, List<ObservedComparison> comparisons,
            List<Recommendation> recommendations, List<String> limitations) {}

    public record ReviewListItem(UUID id, UUID robotRunId, int revision, Window observationWindow,
            Metric primaryMetric, EvidenceStatus evidenceStatus, int publishedOutputCount,
            int analyticsPublicationCount, Instant createdAt) {}

    public record CampaignOption(UUID robotRunId, String robotName, String runStatus,
            Instant startedAt, int intendedOutputCount, int actualOutputCount,
            int publishedOutputCount, UUID latestReviewId) {}

    public record CohortRow(String key, String label, long publicationCount,
            long eligibleByAgeCount, long analyticsPublicationCount, long sampleCount,
            BigDecimal coverage, MetricStatistics metric) {}

    public record CohortComparison(LocalDate from, LocalDate to, Window observationWindow,
            Metric metric, Dimension dimension, List<CohortRow> rows, boolean truncated,
            List<Recommendation> recommendations, List<String> limitations) {}
}
