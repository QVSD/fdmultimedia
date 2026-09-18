package com.fdmultimedia.api.robots;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateRobotRequest(
        @NotNull String name,
        String description,
        @NotNull RobotAutonomyMode autonomyMode,
        @NotNull UUID sourceAssetId,
        UUID targetSocialAccountId,
        @NotNull RobotCadenceType cadenceType,
        Integer cadenceIntervalHours,
        Integer scheduleDelayMinutes,
        Integer maxRunsPerDay) {
}
