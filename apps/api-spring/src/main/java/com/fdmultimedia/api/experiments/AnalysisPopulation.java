package com.fdmultimedia.api.experiments;

/**
 * Item 10: deliberately explicit names rather than "ITT"/"per-protocol"
 * alone — neither population is a formal causal intention-to-treat
 * estimator, since missing outcomes (unpublished/too-young/no-snapshot) are
 * simply excluded rather than imputed. See {@code ExperimentAnalysisService}
 * for exact inclusion criteria.
 *
 * <p>{@code ASSIGNED_OBSERVED}: every ExperimentAssignment for the variant
 * contributes to the funnel; the metric sample includes every assignment
 * with an observed primary-metric value, regardless of protocol deviation.
 * <p>{@code PER_PROTOCOL_OBSERVED}: the metric sample additionally excludes
 * any assignment whose selected Publication has {@code protocolDeviation
 * = true}.
 */
public enum AnalysisPopulation {
    ASSIGNED_OBSERVED,
    PER_PROTOCOL_OBSERVED
}
