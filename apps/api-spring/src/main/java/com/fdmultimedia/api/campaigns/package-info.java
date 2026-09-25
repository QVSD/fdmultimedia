/**
 * Campaign coherence &amp; cross-output content planning (Phase 17E) — a
 * {@code CampaignContentPlan} is an advisory, revisioned coordination layer
 * over one multi-output {@code com.fdmultimedia.api.robots.RobotRun}. It
 * exists purely to give each {@code RobotRunOutput} a distinct editorial
 * role and non-repeating hook/caption/CTA guidance; it never itself creates
 * a Draft, Publication, or schedule, never mutates a Draft's caption, and
 * never changes {@code RobotRunOutput} membership, order, clip boundaries,
 * highlight ranking, or Experiment assignment — those all remain frozen by
 * the time planning starts (see {@code RobotMultiOutputOrchestrator}).
 *
 * <p>Two planners share one {@code CampaignContentPlan}/{@code
 * CampaignContentPlanItem} shape: {@code DeterministicCampaignPlanner}
 * ({@code DETERMINISTIC_CAMPAIGN_V1}, pure Java, no LLM, always available —
 * the mandatory baseline and fallback/test oracle) and an AI path
 * ({@code CAMPAIGN_PLAN_V1}) that reuses the existing distributed Job/Worker
 * pipeline exactly like {@code com.fdmultimedia.api.contentsuggestions}'
 * {@code GENERATE_SOCIAL_COPY} does ({@code GENERATE_CAMPAIGN_PLAN}): this
 * package builds and owns the entire prompt, the Worker only invokes its
 * already-configured provider and returns raw output, and every field of
 * that output is authoritatively validated here before anything is
 * persisted — output-ID membership/uniqueness, a controlled role enum,
 * bounded text lengths, and deterministic near-duplicate hook detection
 * (never trusting the model's own uniqueness claim). Regenerating never
 * mutates an existing revision; it creates a new one and supersedes the old
 * "current" pointer, while every already-created {@code ContentSuggestion}/
 * {@code PublicationAttribution} keeps pointing at whichever revision it
 * actually consumed.
 *
 * <p>Applying a plan is either automatic (a deterministic plan, or an AI
 * plan under {@code AI_PLAN_AND_APPLY}) or an explicit human action through
 * {@code CampaignContentPlanService.apply} ({@code AI_PLAN_FOR_REVIEW}) —
 * either way, applying a campaign plan only makes its guidance available to
 * {@code com.fdmultimedia.api.contentsuggestions.ContentSuggestionService}
 * for the next SOCIAL_COPY generation; it never itself approves a
 * suggestion, a Robot approval, or a publish schedule. A Robot whose
 * {@code CampaignPlanningPolicy} is {@code NO_CAMPAIGN_PLAN} (the default)
 * never creates a plan at all — every pre-17E single- and multi-output
 * behavior is unchanged unless a Robot is explicitly reconfigured.
 */
package com.fdmultimedia.api.campaigns;
