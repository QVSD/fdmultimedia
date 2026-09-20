package com.fdmultimedia.api.experiments;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Purely descriptive evidence for one variant — never a winner/score. Funnel
 * counts (item 45) are exposed explicitly rather than hidden: an assigned
 * RobotRun that never reached Publication is visible as attrition, not
 * silently dropped (item 46).
 */
public record ExperimentOutcomeVariant(
        UUID variantId,
        ExperimentVariantKey variantKey,
        String label,
        long assignedRuns,
        long failedRuns,
        long runsWithDraft,
        long publishedCount,
        long eligibleByAgeCount,
        long analyticsPublicationCount,
        long metricSampleCount,
        BigDecimal coverage,
        BigDecimal average,
        BigDecimal median) {
}
