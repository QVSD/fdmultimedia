package com.fdmultimedia.api.campaigns;

import java.util.Set;

public enum CampaignPlanStatus {
    GENERATING,
    READY_FOR_REVIEW,
    APPLIED,
    REJECTED,
    FAILED;

    private static final Set<CampaignPlanStatus> TERMINAL = Set.of(APPLIED, REJECTED, FAILED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }
}
