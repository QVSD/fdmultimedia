package com.fdmultimedia.api.experiments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExperimentSummary(
        UUID id,
        String name,
        String description,
        String hypothesis,
        ExperimentFactor factor,
        ExperimentStatus status,
        AssignmentStrategy assignmentStrategy,
        String targetObservationWindow,
        String primaryMetric,
        List<ExperimentVariantSummary> variants,
        Instant createdAt,
        Instant updatedAt,
        Instant activatedAt,
        Instant stoppedAt) {
}
