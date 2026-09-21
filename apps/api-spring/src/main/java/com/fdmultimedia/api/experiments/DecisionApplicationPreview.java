package com.fdmultimedia.api.experiments;

import java.util.List;
import java.util.UUID;

public record DecisionApplicationPreview(String applicationVersion, UUID experimentId, String experimentName,
        UUID decisionId, String decisionType, String decisionReadinessSnapshot, String decisionPopulation,
        String decisionEvidenceFingerprint, String selectedVariantKey, UUID robotId, String robotName,
        UUID robotExperimentId, PersonaRef currentPersona, PersonaRef targetPersona, boolean noOp, boolean eligible,
        List<String> blockingReasons, List<String> warnings, List<Change> changes, String previewFingerprint) {
    public record PersonaRef(UUID id, String name, String status) {}
    public record Change(String field, PersonaRef before, PersonaRef after) {}
}
