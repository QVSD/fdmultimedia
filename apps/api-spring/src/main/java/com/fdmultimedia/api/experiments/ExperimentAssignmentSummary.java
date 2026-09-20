package com.fdmultimedia.api.experiments;

import java.time.Instant;
import java.util.UUID;

public record ExperimentAssignmentSummary(
        UUID id,
        UUID experimentId,
        UUID experimentVariantId,
        ExperimentVariantKey variantKey,
        UUID robotRunId,
        Instant assignedAt,
        AssignmentStrategy assignmentStrategy,
        ExperimentFactor factor,
        String factorValueNameSnapshot) {
}
