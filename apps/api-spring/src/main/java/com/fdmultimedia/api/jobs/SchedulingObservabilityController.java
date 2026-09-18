package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scheduling")
public class SchedulingObservabilityController {

    private final SchedulingObservabilityService service;

    public SchedulingObservabilityController(SchedulingObservabilityService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public SchedulingOverview overview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "24h") String window) {
        return service.overview(principal, window);
    }

    @GetMapping("/workers")
    public List<WorkerPerformanceSummary> workers(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "24h") String window) {
        return service.workers(principal, window);
    }

    @GetMapping("/decisions")
    public List<SchedulingDecisionSummary> decisions(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "24h") String window,
            @RequestParam(defaultValue = "25") int limit) {
        return service.decisions(principal, window, limit);
    }
}
