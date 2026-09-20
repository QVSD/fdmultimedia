package com.fdmultimedia.api.experiments;

/**
 * Exactly one factor varies per Experiment (never a multi-factor/multivariate
 * design). PERSONA is the only factor Phase 14A implements: Variant A/B are
 * two distinct, workspace-scoped, ACTIVE-at-activation Personas. AI_POLICY
 * was considered (see docs/ARCHITECTURE.md) and deliberately deferred — it
 * would conflate the experimental factor with the pre-existing human-review
 * gate (GENERATE_FOR_REVIEW vs. GENERATE_AND_APPLY), and NO_AI has no
 * treatment to assign at all — rather than force an ambiguous design.
 */
public enum ExperimentFactor {
    PERSONA
}
