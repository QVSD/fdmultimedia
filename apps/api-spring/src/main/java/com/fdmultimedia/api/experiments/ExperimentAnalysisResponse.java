package com.fdmultimedia.api.experiments;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Item 51: deliberately has no {@code winner}/{@code recommendedVariant}/
 * {@code deployVariant} field anywhere in this type or its nested records —
 * by omission, not by a runtime check (mirrors {@code ExperimentOutcome}'s
 * own "no winner ever" discipline from Phase 14A).
 */
public record ExperimentAnalysisResponse(
        String analysisVersion,
        UUID experimentId,
        String experimentName,
        ExperimentStatus experimentStatus,
        ExperimentFactor factor,
        String targetObservationWindow,
        String primaryMetric,
        BigDecimal confidenceLevel,
        String activeExperimentWarning,
        List<String> limitations,
        ExperimentPopulationAnalysis assignedObserved,
        ExperimentPopulationAnalysis perProtocolObserved) {
}
