package com.fdmultimedia.api.campaigns;

import com.fdmultimedia.api.assets.WorkerImportAuthorizationRequest;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/worker-agent/campaign-plans")
public class WorkerCampaignPlanController {

    private final CampaignContentPlanService service;

    public WorkerCampaignPlanController(CampaignContentPlanService service) {
        this.service = service;
    }

    @PostMapping("/{jobId}/authorization")
    public WorkerCampaignPlanAuthorizationResponse authorization(
            Authentication authentication, @PathVariable UUID jobId, @Valid @RequestBody WorkerImportAuthorizationRequest request) {
        return service.authorizeWorkerGeneration((WorkerPrincipal) authentication.getPrincipal(), jobId, request.machineIdentifier());
    }

    @PostMapping("/{jobId}/complete")
    public CampaignContentPlanSummary complete(
            Authentication authentication, @PathVariable UUID jobId, @Valid @RequestBody WorkerCampaignPlanCompletionRequest request) {
        return service.completeWorkerGeneration((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/{jobId}/fail")
    public CampaignContentPlanSummary fail(
            Authentication authentication, @PathVariable UUID jobId, @Valid @RequestBody WorkerCampaignPlanFailureRequest request) {
        return service.failWorkerGeneration((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }
}
