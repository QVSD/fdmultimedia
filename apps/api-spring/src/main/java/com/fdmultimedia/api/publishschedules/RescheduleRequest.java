package com.fdmultimedia.api.publishschedules;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record RescheduleRequest(@NotNull Instant scheduledFor) {
}
