package com.fdmultimedia.api.experiments;

/**
 * Never {@code SUCCESSFUL_VARIANT_A}/{@code WINNER_A} — this enum only ever
 * describes whether inferential statistics (CI/p-value/effect size) can be
 * computed, never which variant is "better." Descriptive statistics (mean,
 * median, min, max) are returned regardless of status; only the inferential
 * fields (standard error, degrees of freedom, confidence interval, p-value,
 * effect size) are null unless status is {@code READY}.
 */
public enum AnalysisStatus {
    /** No assignment in this population has an observed primary-metric value in either arm. */
    NO_OBSERVATIONS,
    /** Observed outcomes span more than one analytics provider; pooling a normalized metric across providers is not defensible (item 74). */
    MIXED_PROVIDERS,
    /** At least one arm has fewer observed outcomes than {@code app.experiment-analysis.min-sample-per-variant}. */
    INSUFFICIENT_SAMPLE,
    /** Both arms meet the minimum sample, but the pooled Welch variance term is exactly zero (e.g. every observed value identical in both arms) — the t-statistic/df would be undefined. */
    INSUFFICIENT_VARIANCE,
    /** Full Welch's t-test evidence (SE, df, 95% CI, p-value, Hedges' g) was computed. */
    READY
}
