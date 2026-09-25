package com.fdmultimedia.api.campaigns;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WorkerCampaignPlanFailureRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID planId,
        String errorCode,
        String errorMessage,
        Boolean terminal) {
}
