package com.fdmultimedia.api.experiments;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record DecisionApplicationPreviewRequest(@NotNull UUID robotId) {}
