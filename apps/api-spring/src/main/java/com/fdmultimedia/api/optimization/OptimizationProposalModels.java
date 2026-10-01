package com.fdmultimedia.api.optimization;

import com.fdmultimedia.api.analytics.DashboardQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OptimizationProposalModels {
    private OptimizationProposalModels() {}
    public enum Status { READY_FOR_REVIEW, APPROVED, REJECTED, MATERIALIZED, STALE, FAILED }
    public enum Statistic { MEDIAN }
    public enum Direction { HIGHER_OBSERVED, LOWER_OBSERVED, SIMILAR_OBSERVED }
    public record CreateRequest(UUID sourceReviewId, UUID candidatePersonaId) {}
    public record Summary(UUID id, UUID sourceReviewId, int revision, boolean current,
            String engineVersion, String factor, Status status,
            UUID baselinePersonaId, String baselinePersonaName,
            UUID candidatePersonaId, String candidatePersonaName,
            DashboardQuery.Metric metric, Statistic statistic, DashboardQuery.Window observationWindow,
            String provider, Instant cohortFrom, Instant cohortTo,
            int baselineSample, int candidateSample, int baselineEligible, int candidateEligible,
            BigDecimal baselineCoverage, BigDecimal candidateCoverage,
            BigDecimal baselineValue, BigDecimal candidateValue,
            BigDecimal absoluteDifference, BigDecimal relativeDifferencePercent,
            Direction direction, String evidenceFingerprint, String rationale, String limitation,
            UUID materializedExperimentId, Instant createdAt, Instant reviewedAt, Instant materializedAt) {}
    public record Eligibility(boolean eligible, String reasonCode, UUID baselinePersonaId,
            String baselinePersonaName, DashboardQuery.Metric metric, DashboardQuery.Window observationWindow,
            String provider, int minimumSample, BigDecimal minimumCoverage,
            BigDecimal materialDifferencePercent, List<String> limitations) {}
}
