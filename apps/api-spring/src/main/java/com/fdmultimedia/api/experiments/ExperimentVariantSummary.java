package com.fdmultimedia.api.experiments;

import java.util.UUID;

public record ExperimentVariantSummary(
        UUID id,
        ExperimentVariantKey variantKey,
        String label,
        UUID personaId,
        String personaNameSnapshot,
        boolean frozen,
        long assignedCount) {
}
