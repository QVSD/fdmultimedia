package com.fdmultimedia.api.experiments;

import java.time.Instant;
import java.util.UUID;

public record DecisionApplicationRecord(UUID id, UUID experimentId, String experimentNameSnapshot,
        UUID experimentDecisionId, UUID robotId, String robotNameSnapshot, String applicationVersion,
        String status, String selectedVariantKey, UUID targetPersonaId, String targetPersonaNameSnapshot,
        UUID previousPersonaId, String previousPersonaNameSnapshot, String previewFingerprint,
        String decisionEvidenceFingerprint, UUID requestedByUserId, Instant requestedAt, UUID appliedByUserId,
        Instant appliedAt, Instant robotUpdatedAtBefore, Instant robotUpdatedAtAfter, boolean noOp,
        boolean confirmedOlderDecision, boolean confirmedNotReadyDecision, boolean confirmedActiveExperiment,
        UUID rollbackOfApplicationId, String idempotencyKey) {}
