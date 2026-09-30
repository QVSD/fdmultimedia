package com.fdmultimedia.api.robots;

/**
 * Phase 17F: whether a multi-output Robot's per-output social copy is
 * generated independently (Phase 17E behavior, unchanged) or coordinated in
 * one bounded cross-output generation that avoids repetition and can
 * reference series position — never a replacement for {@link
 * CampaignPlanningPolicy}, which decides each output's *role*; this decides
 * how the *actual copy text* across outputs stays coherent. Independent of
 * {@link RobotAiPolicy} and {@code RobotAutonomyMode} — never merge these
 * axes (same discipline as {@code CampaignPlanningPolicy}).
 *
 * <p>Only meaningful for a multi-output ({@code TOP_DIVERSE_HIGHLIGHTS})
 * Robot with an active {@code CampaignPlanningPolicy}: coordinated copy is
 * generated from an <em>applied</em> {@code CampaignContentPlan} revision,
 * so a Robot with {@code CampaignPlanningPolicy.NO_CAMPAIGN_PLAN} can never
 * produce a plan to coordinate around. {@code RobotService} rejects that
 * combination, and rejects {@code RobotAiPolicy.NO_AI} combined with a
 * coordinated policy, at Robot create/update time (items 38/39) — coordinated
 * generation is inherently an AI operation, never a deterministic in-process
 * planner the way {@code CampaignPlanningPolicy.DETERMINISTIC_PLAN} is.
 */
public enum CopyCoordinationPolicy {
    /** Default — every historical/new Robot without explicit configuration. Byte-identical to Phase 17E: no CampaignCopySet is ever created. */
    INDEPENDENT_COPY,
    /** One bounded coordinated generation produces a CampaignCopySet a human must Apply before any ContentSuggestion is materialized. */
    COORDINATED_COPY_FOR_REVIEW,
    /** Same coordinated generation, but the resulting CampaignCopySet auto-applies — whether the resulting ContentSuggestions themselves auto-apply to their Drafts still depends on RobotAiPolicy. */
    COORDINATED_COPY_AND_APPLY
}
