package com.fdmultimedia.api.experiments;

import java.util.List;
import java.util.UUID;

/**
 * Deliberately has no {@code winner}/{@code confidence}/{@code significance}
 * field (item 42) — descriptive evidence only, at the Experiment's own fixed
 * {@code targetObservationWindow}/{@code primaryMetric} (items 38/39/43),
 * reusing Phase 13B's exact snapshot-selection semantics.
 */
public record ExperimentOutcome(
        UUID experimentId,
        String targetObservationWindow,
        String primaryMetric,
        String disclaimer,
        List<String> notices,
        List<ExperimentOutcomeVariant> variants) {
}
