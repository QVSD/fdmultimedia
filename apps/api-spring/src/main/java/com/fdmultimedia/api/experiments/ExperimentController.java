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
    private final ExperimentAnalysisService analysisService;
    private final ExperimentDecisionReadinessService readinessService;
    private final ExperimentDecisionService decisionService;
    private final DecisionApplicationService applicationService;

    public ExperimentController(ExperimentService service, ExperimentOutcomeService outcomeService, ExperimentAnalysisService analysisService,
            ExperimentDecisionReadinessService readinessService, ExperimentDecisionService decisionService,
            DecisionApplicationService applicationService) {
        this.service = service;
        this.outcomeService = outcomeService;
        this.analysisService = analysisService;
        this.readinessService = readinessService;
        this.decisionService = decisionService;
        this.applicationService = applicationService;
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

    /** Item 53: no metric/window query params — the Experiment's own frozen primary metric/target window are always used. */
    @GetMapping("/{experimentId}/analysis")
    public ExperimentAnalysisResponse analysis(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId) {
        return analysisService.analyze(principal, experimentId);
    }

    @GetMapping("/{experimentId}/decision-readiness")
    public ExperimentDecisionReadiness readiness(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId) {
        return readinessService.read(principal, experimentId);
    }

    @PostMapping("/{experimentId}/decisions")
    public ExperimentDecisionRecord decide(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId,
            @Valid @RequestBody ExperimentDecisionRequest request) {
        return decisionService.create(principal, experimentId, request);
    }

    @GetMapping("/{experimentId}/decisions")
    public List<ExperimentDecisionRecord> decisions(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId) {
        return decisionService.history(principal, experimentId);
    }

    @GetMapping("/{experimentId}/decisions/{decisionId}")
    public ExperimentDecisionRecord decision(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID experimentId,
            @PathVariable UUID decisionId) {
        return decisionService.detail(principal, experimentId, decisionId);
    }

    @PostMapping("/{experimentId}/decisions/{decisionId}/application-preview")
    public DecisionApplicationPreview applicationPreview(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID experimentId, @PathVariable UUID decisionId,
            @Valid @RequestBody DecisionApplicationPreviewRequest request) {
        return applicationService.preview(principal, experimentId, decisionId, request.robotId());
    }

    @PostMapping("/{experimentId}/decisions/{decisionId}/applications")
    public DecisionApplicationRecord applyDecision(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID experimentId, @PathVariable UUID decisionId,
            @Valid @RequestBody DecisionApplicationRequest request) {
        return applicationService.apply(principal, experimentId, decisionId, request);
    }

    @GetMapping("/{experimentId}/decision-applications")
    public List<DecisionApplicationRecord> applications(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID experimentId) {
        return applicationService.history(principal, experimentId);
    }

    @GetMapping("/decision-applications/{applicationId}")
    public DecisionApplicationRecord application(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID applicationId) {
        return applicationService.detail(principal, applicationId);
    }

    @PostMapping("/decision-applications/{applicationId}/rollback-preview")
    public DecisionApplicationPreview rollbackPreview(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID applicationId) {
        return applicationService.rollbackPreview(principal, applicationId);
    }

    @PostMapping("/decision-applications/{applicationId}/rollback")
    public DecisionApplicationRecord rollback(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID applicationId, @Valid @RequestBody DecisionRollbackRequest request) {
        return applicationService.rollback(principal, applicationId, request);
    }
}
