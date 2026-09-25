package com.fdmultimedia.api.campaigns;

/**
 * Bounded, controlled vocabulary for a plan item's editorial role — never a
 * free-form string, from either the deterministic planner or a validated AI
 * response (see item 45). Carries no claim of engagement/performance impact.
 */
public enum CampaignPlanRole {
    INTRODUCTION,
    DEEP_DIVE,
    SUPPORTING_POINT,
    CONCLUSION,
    STANDALONE
}
