package com.fdmultimedia.api.campaigns;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record WorkerCampaignPlanCompletionRequest(
        @NotBlank String machineIdentifier,
        @NotNull UUID planId,
        String campaignTitle,
        String campaignAngle,
        @Valid @NotNull List<WorkerCampaignPlanItemRequest> items) {
}
