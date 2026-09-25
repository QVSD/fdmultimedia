package com.fdmultimedia.api.robots;

/**
 * Independent of {@link RobotHighlightStrategy}/{@link RobotAiPolicy} — this
 * axis only decides whether/how a multi-output {@code RobotRun} gets a
 * cross-output {@code CampaignContentPlan} coordinating messaging across its
 * {@code RobotRunOutput}s. It never changes highlight selection, candidate
 * ranking, clip boundaries, or Experiment assignment (Phase 17E scope).
 *
 * <p>NO_CAMPAIGN_PLAN (default): exact pre-17E behavior — no plan is ever
 * created, so every existing single-output ({@code TOP_HIGHLIGHT}) and
 * multi-output ({@code TOP_DIVERSE_HIGHLIGHTS}) Robot is unaffected unless
 * explicitly reconfigured.
 * <p>DETERMINISTIC_PLAN: {@code DETERMINISTIC_CAMPAIGN_V1} — pure Java, no
 * LLM, always available — generates and applies a plan synchronously, no
 * review step (its output is bounded and predictable by construction).
 * <p>AI_PLAN_FOR_REVIEW: an AI-generated plan pauses at
 * {@code READY_FOR_REVIEW} until a human explicitly applies or rejects it.
 * <p>AI_PLAN_AND_APPLY: an AI-generated plan is applied automatically once
 * generation succeeds — this does not change the Robot's own
 * {@link RobotAiPolicy} for the resulting per-output SOCIAL_COPY, which is
 * still governed independently (see item 38).
 */
public enum CampaignPlanningPolicy {
    NO_CAMPAIGN_PLAN,
    DETERMINISTIC_PLAN,
    AI_PLAN_FOR_REVIEW,
    AI_PLAN_AND_APPLY
}
