package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService service;
    private final ExperimentOutcomeService outcomeService;

    public ExperimentController(ExperimentService service, ExperimentOutcomeService outcomeService) {
        this.service = service;
        this.outcomeService = outcomeService;
    }

    @GetMapping
    public List<ExperimentSummary> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.list(principal);
    }

    @PostMapping
    public ExperimentSummary create(@AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody CreateExperimentRequest request) {
        return service.create(principal, request);
    }

    @GetMapping("/{experimentId}")
    public ExperimentSummary get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId) {
        return service.getFor(principal, experimentId);
    }

    @PatchMapping("/{experimentId}")
    public ExperimentSummary update(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId,
            @Valid @RequestBody UpdateExperimentRequest request) {
        return service.update(principal, experimentId, request);
    }

    @PostMapping("/{experimentId}/activate")
    public ExperimentSummary activate(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId) {
        return service.activate(principal, experimentId);
    }

    @PostMapping("/{experimentId}/pause")
    public ExperimentSummary pause(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId) {
        return service.pause(principal, experimentId);
    }

    @PostMapping("/{experimentId}/resume")
    public ExperimentSummary resume(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId) {
        return service.resume(principal, experimentId);
    }

    @PostMapping("/{experimentId}/complete")
    public ExperimentSummary complete(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId) {
        return service.complete(principal, experimentId);
    }

    @PostMapping("/{experimentId}/cancel")
    public ExperimentSummary cancel(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId) {
        return service.cancel(principal, experimentId);
    }

    @GetMapping("/{experimentId}/assignments")
    public List<ExperimentAssignmentSummary> assignments(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId) {
        return service.assignmentsFor(principal, experimentId);
    }

    @GetMapping("/{experimentId}/outcomes")
    public ExperimentOutcome outcomes(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId) {
        return outcomeService.outcomes(principal, experimentId);
    }
}
