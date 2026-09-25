package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateRobotRequest(
        @NotNull String name,
        String description,
        @NotNull RobotAutonomyMode autonomyMode,
        @NotNull RobotSourcePolicy sourcePolicy,
        UUID sourceAssetId,
        UUID contentSourceId,
        RobotSelectionPolicy selectionPolicy,
        UUID targetSocialAccountId,
        @NotNull RobotCadenceType cadenceType,
        Integer cadenceIntervalHours,
        Integer scheduleDelayMinutes,
        Integer maxRunsPerDay,
        RobotAiPolicy aiPolicy,
        UUID personaId,
        SuggestionLanguage aiLanguageOverride,
        SuggestionTone aiToneOverride,
        UUID experimentId,
        RobotHighlightStrategy highlightStrategy,
        Integer highlightCount,
        Integer outputSpacingMinutes,
        CampaignPlanningPolicy campaignPlanningPolicy) {

    /** Backward-compatible overload (no campaign planning) — pre-17E call sites keep working unchanged. */
    public CreateRobotRequest(
            String name, String description, RobotAutonomyMode autonomyMode, RobotSourcePolicy sourcePolicy,
            UUID sourceAssetId, UUID contentSourceId, RobotSelectionPolicy selectionPolicy,
            UUID targetSocialAccountId, RobotCadenceType cadenceType, Integer cadenceIntervalHours,
            Integer scheduleDelayMinutes, Integer maxRunsPerDay, RobotAiPolicy aiPolicy, UUID personaId,
            SuggestionLanguage aiLanguageOverride, SuggestionTone aiToneOverride, UUID experimentId,
            RobotHighlightStrategy highlightStrategy, Integer highlightCount, Integer outputSpacingMinutes) {
        this(name, description, autonomyMode, sourcePolicy, sourceAssetId, contentSourceId, selectionPolicy,
                targetSocialAccountId, cadenceType, cadenceIntervalHours, scheduleDelayMinutes, maxRunsPerDay,
                aiPolicy, personaId, aiLanguageOverride, aiToneOverride, experimentId, highlightStrategy,
                highlightCount, outputSpacingMinutes, null);
    }

    /** Backward-compatible overload (NO_AI, no Persona, no Experiment) — Phase 11C/11D call sites keep working unchanged. */
    public CreateRobotRequest(
            String name,
            String description,
            RobotAutonomyMode autonomyMode,
            RobotSourcePolicy sourcePolicy,
            UUID sourceAssetId,
            UUID contentSourceId,
            RobotSelectionPolicy selectionPolicy,
            UUID targetSocialAccountId,
            RobotCadenceType cadenceType,
            Integer cadenceIntervalHours,
            Integer scheduleDelayMinutes,
            Integer maxRunsPerDay) {
        this(name, description, autonomyMode, sourcePolicy, sourceAssetId, contentSourceId, selectionPolicy,
                targetSocialAccountId, cadenceType, cadenceIntervalHours, scheduleDelayMinutes, maxRunsPerDay,
                null, null, null, null, null, null, null, null);
    }

    /** Backward-compatible overload (no Experiment) — Phase 12C call sites keep working unchanged. */
    public CreateRobotRequest(
            String name,
            String description,
            RobotAutonomyMode autonomyMode,
            RobotSourcePolicy sourcePolicy,
            UUID sourceAssetId,
            UUID contentSourceId,
            RobotSelectionPolicy selectionPolicy,
            UUID targetSocialAccountId,
            RobotCadenceType cadenceType,
            Integer cadenceIntervalHours,
            Integer scheduleDelayMinutes,
            Integer maxRunsPerDay,
            RobotAiPolicy aiPolicy,
            UUID personaId,
            SuggestionLanguage aiLanguageOverride,
            SuggestionTone aiToneOverride) {
        this(name, description, autonomyMode, sourcePolicy, sourceAssetId, contentSourceId, selectionPolicy,
                targetSocialAccountId, cadenceType, cadenceIntervalHours, scheduleDelayMinutes, maxRunsPerDay,
                aiPolicy, personaId, aiLanguageOverride, aiToneOverride, null, null, null, null);
    }

    /** Backward-compatible overload with Experiment — Phase 14+ callers keep their original shape. */
    public CreateRobotRequest(
            String name, String description, RobotAutonomyMode autonomyMode, RobotSourcePolicy sourcePolicy,
            UUID sourceAssetId, UUID contentSourceId, RobotSelectionPolicy selectionPolicy,
            UUID targetSocialAccountId, RobotCadenceType cadenceType, Integer cadenceIntervalHours,
            Integer scheduleDelayMinutes, Integer maxRunsPerDay, RobotAiPolicy aiPolicy, UUID personaId,
            SuggestionLanguage aiLanguageOverride, SuggestionTone aiToneOverride, UUID experimentId) {
        this(name, description, autonomyMode, sourcePolicy, sourceAssetId, contentSourceId, selectionPolicy,
                targetSocialAccountId, cadenceType, cadenceIntervalHours, scheduleDelayMinutes, maxRunsPerDay,
                aiPolicy, personaId, aiLanguageOverride, aiToneOverride, experimentId, null, null, null);
    }
}
