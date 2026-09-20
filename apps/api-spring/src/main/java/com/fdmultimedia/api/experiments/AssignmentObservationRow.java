package com.fdmultimedia.api.experiments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Item 56: the canonical, unpersisted read model — exactly one row per
 * {@link ExperimentAssignment}, never per Publication. Built once by
 * {@link ExperimentAnalysisStore} and aggregated in memory by {@link
 * ExperimentAnalysisService}; both the funnel and the statistical estimate
 * are computed from the same rows so they can never silently disagree about
 * cohort membership (item 55).
 */
public record AssignmentObservationRow(
        UUID assignmentId,
        UUID variantId,
        String robotRunStatus,
        UUID publicationId,
        Instant publishedAt,
        String provider,
        Boolean protocolDeviation,
        boolean eligibleByAge,
        boolean tooYoung,
        UUID snapshotId,
        BigDecimal metricValue) {
}
