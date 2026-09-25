package com.fdmultimedia.api.campaigns;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Campaign-plan-specific bounds only. Provider/model/the AI kill switch are
 * reused verbatim from {@code ContentAiProperties} (item 19/49) — this is
 * the same AI infrastructure, not a separate one.
 */
@ConfigurationProperties(prefix = "app.campaign-plans")
public class CampaignPlanProperties {

    public static final String DETERMINISTIC_VERSION = "DETERMINISTIC_CAMPAIGN_V1";
    public static final String AI_PROMPT_VERSION = "CAMPAIGN_PLAN_V1";

    private int maxCampaignTitleLength = 120;
    private int maxCampaignAngleLength = 500;
    private int maxHookGuidanceLength = 300;
    private int maxCaptionGuidanceLength = 800;
    private int maxCtaGuidanceLength = 300;
    private int maxAvoidanceGuidanceLength = 500;
    private int maxPromptCharacters = 20000;
    private int maxEvidenceExcerptCharacters = 600;
    private BigDecimal nearDuplicateGuidanceThreshold = new BigDecimal("0.75");

    public int getMaxCampaignTitleLength() { return maxCampaignTitleLength; }
    public void setMaxCampaignTitleLength(int v) { this.maxCampaignTitleLength = v; }
    public int getMaxCampaignAngleLength() { return maxCampaignAngleLength; }
    public void setMaxCampaignAngleLength(int v) { this.maxCampaignAngleLength = v; }
    public int getMaxHookGuidanceLength() { return maxHookGuidanceLength; }
    public void setMaxHookGuidanceLength(int v) { this.maxHookGuidanceLength = v; }
    public int getMaxCaptionGuidanceLength() { return maxCaptionGuidanceLength; }
    public void setMaxCaptionGuidanceLength(int v) { this.maxCaptionGuidanceLength = v; }
    public int getMaxCtaGuidanceLength() { return maxCtaGuidanceLength; }
    public void setMaxCtaGuidanceLength(int v) { this.maxCtaGuidanceLength = v; }
    public int getMaxAvoidanceGuidanceLength() { return maxAvoidanceGuidanceLength; }
    public void setMaxAvoidanceGuidanceLength(int v) { this.maxAvoidanceGuidanceLength = v; }
    public int getMaxPromptCharacters() { return maxPromptCharacters; }
    public void setMaxPromptCharacters(int v) { this.maxPromptCharacters = v; }
    public int getMaxEvidenceExcerptCharacters() { return maxEvidenceExcerptCharacters; }
    public void setMaxEvidenceExcerptCharacters(int v) { this.maxEvidenceExcerptCharacters = v; }
    public BigDecimal getNearDuplicateGuidanceThreshold() { return nearDuplicateGuidanceThreshold; }
    public void setNearDuplicateGuidanceThreshold(BigDecimal v) { this.nearDuplicateGuidanceThreshold = v; }
}
