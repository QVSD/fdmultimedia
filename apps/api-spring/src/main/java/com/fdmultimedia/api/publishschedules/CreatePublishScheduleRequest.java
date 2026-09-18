package com.fdmultimedia.api.publishschedules;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record CreatePublishScheduleRequest(
        @NotNull UUID socialAccountId,
        @NotNull Instant scheduledFor) {
}
