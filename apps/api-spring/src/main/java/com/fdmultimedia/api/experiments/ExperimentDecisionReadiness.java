package com.fdmultimedia.api.experiments;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ExperimentDecisionReadiness(String guardrailVersion, UUID experimentId, ExperimentStatus experimentStatus,
        String analysisVersion, String primaryMetric, String targetObservationWindow, BigDecimal minimumPracticalEffect,
        Population assignedObserved, Population perProtocolObserved, String practicalEffectNotice, String intervalNotice) {
    public record Check(String code, String status, String message, BigDecimal actualValue, BigDecimal thresholdValue) {}
    public record Population(AnalysisPopulation population, String readinessStatus, List<Check> checks,
            String direction, String practicalEffectStatus, String intervalPracticalRelationship,
            List<String> nextSteps, ExperimentPopulationAnalysis evidence) {}
}
