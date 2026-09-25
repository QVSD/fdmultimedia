package com.fdmultimedia.api.campaigns;

import com.fdmultimedia.api.assets.MediaAsset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code DETERMINISTIC_CAMPAIGN_V1}: pure Java, no LLM, always available —
 * the mandatory baseline and fallback/test oracle (item 5). Assigns bounded
 * roles purely from output count and position (item 6/46) and generates
 * simple, honest, generic guidance — it never claims human-level creative
 * strategy or invents facts about the source.
 */
@Component
public class DeterministicCampaignPlanner {

    public record PlannedItem(int sequence, CampaignPlanRole role, String hookGuidance, String captionGuidance,
            String ctaGuidance, String avoidRepetitionGuidance) {
    }

    public record Plan(String campaignTitle, String campaignAngle, List<PlannedItem> items) {
    }

    /** {@code outputCount} must be 1..5 (the same bound {@code RobotRunOutput} fan-out already enforces). */
    public Plan plan(int outputCount, MediaAsset sourceAsset) {
        List<CampaignPlanRole> roles = roles(outputCount);
        List<PlannedItem> items = new ArrayList<>(outputCount);
        for (int i = 0; i < outputCount; i++) {
            int sequence = i + 1;
            CampaignPlanRole role = roles.get(i);
            items.add(new PlannedItem(sequence, role, hookGuidance(role), captionGuidance(role, sequence, outputCount),
                    null, avoidRepetitionGuidance(outputCount)));
        }
        return new Plan(campaignTitle(sourceAsset), campaignAngle(outputCount), items);
    }

    /** Item 6: bounded deterministic role mapping for every supported count, 1..5. */
    List<CampaignPlanRole> roles(int outputCount) {
        return switch (outputCount) {
            case 1 -> List.of(CampaignPlanRole.STANDALONE);
            case 2 -> List.of(CampaignPlanRole.INTRODUCTION, CampaignPlanRole.CONCLUSION);
            case 3 -> List.of(CampaignPlanRole.INTRODUCTION, CampaignPlanRole.DEEP_DIVE, CampaignPlanRole.CONCLUSION);
            case 4 -> List.of(CampaignPlanRole.INTRODUCTION, CampaignPlanRole.DEEP_DIVE,
                    CampaignPlanRole.SUPPORTING_POINT, CampaignPlanRole.CONCLUSION);
            case 5 -> List.of(CampaignPlanRole.INTRODUCTION, CampaignPlanRole.DEEP_DIVE, CampaignPlanRole.DEEP_DIVE,
                    CampaignPlanRole.SUPPORTING_POINT, CampaignPlanRole.CONCLUSION);
            default -> throw new IllegalArgumentException("outputCount must be between 1 and 5");
        };
    }

    private String hookGuidance(CampaignPlanRole role) {
        return switch (role) {
            case INTRODUCTION -> "Open by introducing the topic clearly, as the first part of a series.";
            case DEEP_DIVE -> "Lead with the specific detail or example this part focuses on.";
            case SUPPORTING_POINT -> "Lead with the specific supporting point this part covers.";
            case CONCLUSION -> "Open by signaling this wraps up the series with a clear takeaway.";
            case STANDALONE -> "Open with the strongest moment from this clip on its own.";
        };
    }

    private String captionGuidance(CampaignPlanRole role, int sequence, int outputCount) {
        String position = outputCount > 1 ? "Part " + sequence + " of " + outputCount + ". " : "";
        return switch (role) {
            case INTRODUCTION -> position + "Set up the topic for the parts that follow.";
            case DEEP_DIVE -> position + "Go deeper into this specific moment without repeating the introduction.";
            case SUPPORTING_POINT -> position + "Add a distinct supporting point that complements the other parts.";
            case CONCLUSION -> position + "Summarize the series and give the viewer a clear takeaway.";
            case STANDALONE -> "Present this as a complete, self-contained highlight.";
        };
    }

    private String avoidRepetitionGuidance(int outputCount) {
        return outputCount <= 1 ? null
                : "Avoid reusing the same opening phrase, hook, or call to action as the other parts of this series.";
    }

    private String campaignTitle(MediaAsset sourceAsset) {
        String name = sourceAsset == null ? null : sourceAsset.getOriginalFilename();
        return name == null || name.isBlank() ? "Content series" : "Series from " + name;
    }

    private String campaignAngle(int outputCount) {
        return outputCount <= 1
                ? "A single highlight presented on its own."
                : "A " + outputCount + "-part series covering complementary moments from the same source.";
    }
}
