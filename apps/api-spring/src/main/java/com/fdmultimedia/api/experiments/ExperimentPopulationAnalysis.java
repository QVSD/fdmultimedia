package com.fdmultimedia.api.experiments;

public record ExperimentPopulationAnalysis(
        AnalysisPopulation population,
        AnalysisStatus status,
        ExperimentVariantAnalysis variantA,
        ExperimentVariantAnalysis variantB,
        ExperimentEffectEstimate effect) {
}
