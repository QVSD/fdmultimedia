package com.fdmultimedia.api.highlights;

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
@RequestMapping("/api/worker-agent/highlights")
public class WorkerHighlightController {

    private final HighlightService highlightService;

    public WorkerHighlightController(HighlightService highlightService) {
        this.highlightService = highlightService;
    }

    @PostMapping("/{jobId}/authorization")
    public WorkerHighlightAuthorizationResponse authorization(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerImportAuthorizationRequest request) {
        return highlightService.authorizeWorkerAnalysis(
                (WorkerPrincipal) authentication.getPrincipal(),
                jobId,
                request.machineIdentifier());
    }

    @PostMapping("/{jobId}/complete")
    public HighlightAnalysisSummary complete(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerHighlightCompletionRequest request) {
        return highlightService.completeWorkerAnalysis((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/{jobId}/fail")
    public HighlightAnalysisSummary fail(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerHighlightFailureRequest request) {
        return highlightService.failWorkerAnalysis((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }
}
