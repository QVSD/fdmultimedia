package com.fdmultimedia.worker;

import java.util.List;

record CampaignPlanResult(String campaignTitle, String campaignAngle, List<CampaignPlanItemResult> items) {
}
