package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record UpdateRobotRequest(
        @NotNull String name,
        String description,
        @NotNull RobotAutonomyMode autonomyMode,
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
        Integer outputSpacingMinutes) {

    /** Backward-compatible overload (NO_AI, no Persona, no Experiment) — Phase 11C/11D call sites keep working unchanged. */
    public UpdateRobotRequest(
            String name,
            String description,
            RobotAutonomyMode autonomyMode,
            UUID targetSocialAccountId,
            RobotCadenceType cadenceType,
            Integer cadenceIntervalHours,
            Integer scheduleDelayMinutes,
            Integer maxRunsPerDay) {
        this(name, description, autonomyMode, targetSocialAccountId, cadenceType, cadenceIntervalHours,
                scheduleDelayMinutes, maxRunsPerDay, null, null, null, null, null, null, null, null);
    }

    /** Backward-compatible overload (no Experiment) — Phase 12C call sites keep working unchanged. */
    public UpdateRobotRequest(
            String name,
            String description,
            RobotAutonomyMode autonomyMode,
            UUID targetSocialAccountId,
            RobotCadenceType cadenceType,
            Integer cadenceIntervalHours,
            Integer scheduleDelayMinutes,
            Integer maxRunsPerDay,
            RobotAiPolicy aiPolicy,
            UUID personaId,
            SuggestionLanguage aiLanguageOverride,
            SuggestionTone aiToneOverride) {
        this(name, description, autonomyMode, targetSocialAccountId, cadenceType, cadenceIntervalHours,
                scheduleDelayMinutes, maxRunsPerDay, aiPolicy, personaId, aiLanguageOverride, aiToneOverride, null, null, null, null);
    }

    /** Backward-compatible overload with Experiment — Phase 14+ callers keep their original shape. */
    public UpdateRobotRequest(
            String name, String description, RobotAutonomyMode autonomyMode, UUID targetSocialAccountId,
            RobotCadenceType cadenceType, Integer cadenceIntervalHours, Integer scheduleDelayMinutes,
            Integer maxRunsPerDay, RobotAiPolicy aiPolicy, UUID personaId,
            SuggestionLanguage aiLanguageOverride, SuggestionTone aiToneOverride, UUID experimentId) {
        this(name, description, autonomyMode, targetSocialAccountId, cadenceType, cadenceIntervalHours,
                scheduleDelayMinutes, maxRunsPerDay, aiPolicy, personaId, aiLanguageOverride, aiToneOverride,
                experimentId, null, null, null);
    }
}
