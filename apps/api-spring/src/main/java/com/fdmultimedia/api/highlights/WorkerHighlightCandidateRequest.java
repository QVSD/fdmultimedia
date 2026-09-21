package com.fdmultimedia.api.highlights;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record WorkerHighlightCandidateRequest(
        @NotNull Long startMs,
        @NotNull Long endMs,
        @NotNull BigDecimal score,
        @NotBlank String reason,
        BigDecimal hookScore,
        BigDecimal completenessScore,
        BigDecimal informationDensityScore,
        BigDecimal speechDensityScore,
        BigDecimal boundaryScore,
        BigDecimal coverageScore,
        BigDecimal sceneScore,
        BigDecimal audioBoundaryScore,
        BigDecimal repetitionPenalty,
        List<String> explanationLabels,
        String transcriptExcerpt) {

    public WorkerHighlightCandidateRequest(Long startMs, Long endMs, BigDecimal score, String reason) {
        this(startMs, endMs, score, reason, null, null, null, null, null, null, null, null, null, null, null);
    }
}
