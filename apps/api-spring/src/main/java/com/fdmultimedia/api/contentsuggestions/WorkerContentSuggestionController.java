package com.fdmultimedia.api.contentsuggestions;

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
@RequestMapping("/api/worker-agent/content-suggestions")
public class WorkerContentSuggestionController {

    private final ContentSuggestionService service;

    public WorkerContentSuggestionController(ContentSuggestionService service) {
        this.service = service;
    }

    @PostMapping("/{jobId}/authorization")
    public WorkerContentSuggestionAuthorizationResponse authorization(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerImportAuthorizationRequest request) {
        return service.authorizeWorkerGeneration((WorkerPrincipal) authentication.getPrincipal(), jobId, request.machineIdentifier());
    }

    @PostMapping("/{jobId}/complete")
    public ContentSuggestionSummary complete(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerContentSuggestionCompletionRequest request) {
        return service.completeWorkerGeneration((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/{jobId}/fail")
    public ContentSuggestionSummary fail(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerContentSuggestionFailureRequest request) {
        return service.failWorkerGeneration((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }
}
