package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import java.time.Instant;
import java.util.UUID;

public record RobotSummary(
        UUID id,
        String name,
        String description,
        RobotStatus status,
        RobotAutonomyMode autonomyMode,
        RobotHighlightStrategy highlightStrategy,
        RobotSourcePolicy sourcePolicy,
        UUID sourceAssetId,
        String sourceAssetFilename,
        UUID contentSourceId,
        String contentSourceName,
        RobotSelectionPolicy selectionPolicy,
        UUID targetSocialAccountId,
        String targetSocialAccountDisplayName,
        RobotCadenceType cadenceType,
        Integer cadenceIntervalHours,
        Integer scheduleDelayMinutes,
        int maxRunsPerDay,
        RobotAiPolicy aiPolicy,
        UUID personaId,
        String personaName,
        SuggestionLanguage aiLanguageOverride,
        SuggestionTone aiToneOverride,
        UUID experimentId,
        Instant nextRunAt,
        Instant lastRunAt,
        Instant createdAt,
        Instant updatedAt) {
}
