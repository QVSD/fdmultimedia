package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.CampaignPerformanceModels.*;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class CampaignPerformanceController {
    private final CampaignPerformanceService service;
    public CampaignPerformanceController(CampaignPerformanceService service){this.service=service;}

    @PostMapping("/api/robot-runs/{runId}/performance-reviews")
    public Review create(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID runId,
            @RequestBody(required=false) CampaignPerformanceService.CreateRequest request){return service.create(user,runId,request);}
    @GetMapping("/api/robot-runs/{runId}/performance-reviews")
    public List<ReviewListItem> list(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID runId,
            @RequestParam(defaultValue="20") int limit){return service.list(user,runId,limit);}
    @GetMapping("/api/campaign-performance-reviews/{id}")
    public Review get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID id){return service.get(user,id);}
    @GetMapping("/api/analytics/campaign-performance/campaigns")
    public List<CampaignOption> campaigns(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam(defaultValue="50") int limit){return service.options(user,limit);}
    @GetMapping("/api/analytics/campaign-performance/comparison")
    public CohortComparison comparison(@AuthenticationPrincipal AuthenticatedUser user,
            @ModelAttribute CampaignPerformanceService.CohortRequest request){return service.cohorts(user,request);}
}
