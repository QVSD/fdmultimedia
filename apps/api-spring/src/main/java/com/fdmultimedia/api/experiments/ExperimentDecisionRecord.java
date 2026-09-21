package com.fdmultimedia.api.experiments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ExperimentDecisionRecord(UUID id, UUID experimentId, UUID idempotencyKey, String decision,
        String selectedVariantKey, String rationale, UUID decidedByUserId, Instant decidedAt, String guardrailVersion,
        String analysisVersion, String experimentStatusSnapshot, String primaryMetricSnapshot,
        String observationWindowSnapshot, BigDecimal minimumPracticalEffectSnapshot,
        String analysisPopulationSnapshot, long variantASampleSizeSnapshot, long variantBSampleSizeSnapshot,
        BigDecimal absoluteMeanDifferenceSnapshot, BigDecimal confidenceIntervalLowerSnapshot,
        BigDecimal confidenceIntervalUpperSnapshot, BigDecimal pValueSnapshot,
        BigDecimal standardizedEffectSizeSnapshot, String readinessStatusSnapshot, String evidenceFingerprint) {}
