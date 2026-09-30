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
public class CampaignCopySetController {

    private final CampaignCopySetService service;

    public CampaignCopySetController(CampaignCopySetService service) {
        this.service = service;
    }

    @GetMapping("/robot-runs/{runId}/campaign-copy-sets")
    public List<CampaignCopySetSummary> listForRun(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID runId) {
        return service.listForRun(principal, runId);
    }

    @GetMapping("/campaign-copy-sets/{copySetId}")
    public CampaignCopySetSummary get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID copySetId) {
        return service.getFor(principal, copySetId);
    }

    @PostMapping("/campaign-copy-sets/{copySetId}/apply")
    public CampaignCopySetSummary apply(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID copySetId) {
        return service.apply(principal, copySetId);
    }

    @PostMapping("/campaign-copy-sets/{copySetId}/reject")
    public CampaignCopySetSummary reject(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID copySetId) {
        return service.reject(principal, copySetId);
    }

    @PostMapping("/robot-runs/{runId}/campaign-copy-sets/regenerate")
    public CampaignCopySetSummary regenerate(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID runId) {
        return service.regenerate(principal, runId);
    }
}
