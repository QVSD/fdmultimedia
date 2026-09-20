package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.InsightModels.ComparisonResult;
import com.fdmultimedia.api.analytics.InsightModels.InsightsResponse;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics/insights")
public class PerformanceInsightController {
    private final PerformanceInsightService service;

    public PerformanceInsightController(PerformanceInsightService service) {
        this.service = service;
    }

    @GetMapping
    public InsightsResponse insights(@AuthenticationPrincipal AuthenticatedUser user,
            @ModelAttribute PerformanceInsightService.InsightsRequest request) {
        return service.insights(user, request);
    }

    @GetMapping("/compare")
    public ComparisonResult compare(@AuthenticationPrincipal AuthenticatedUser user,
            @ModelAttribute PerformanceInsightService.CompareRequest request) {
        return service.compare(user, request);
    }
}
