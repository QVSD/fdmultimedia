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
        SuggestionTone aiToneOverride) {

    /** Backward-compatible overload (NO_AI, no Persona) — Phase 11C/11D call sites keep working unchanged. */
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
                null, null, null, null);
    }
}
