package com.fdmultimedia.api.campaigns;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Domain-action transitions only — no generic PATCH status endpoint (item 67). */
@RestController
@RequestMapping("/api")
public class CampaignPlanController {

    private final CampaignContentPlanService service;

    public CampaignPlanController(CampaignContentPlanService service) {
        this.service = service;
    }

    @GetMapping("/robot-runs/{runId}/campaign-plans")
    public List<CampaignContentPlanSummary> listForRun(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID runId) {
        return service.listForRun(principal, runId);
    }

    @GetMapping("/campaign-plans/{planId}")
    public CampaignContentPlanSummary get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID planId) {
        return service.getFor(principal, planId);
    }

    @PostMapping("/campaign-plans/{planId}/apply")
    public CampaignContentPlanSummary apply(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID planId) {
        return service.apply(principal, planId);
    }

    @PostMapping("/campaign-plans/{planId}/reject")
    public CampaignContentPlanSummary reject(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID planId) {
        return service.reject(principal, planId);
    }

    @PostMapping("/robot-runs/{runId}/campaign-plans/regenerate")
    public CampaignContentPlanSummary regenerate(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID runId) {
        return service.regenerate(principal, runId);
    }
}
