package com.fdmultimedia.api.assets;

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
@RequestMapping("/api/worker-agent/assets/imports")
public class WorkerMediaImportController {

    private final MediaAssetService mediaAssetService;

    public WorkerMediaImportController(MediaAssetService mediaAssetService) {
        this.mediaAssetService = mediaAssetService;
    }

    @PostMapping("/{jobId}/authorization")
    public WorkerImportAuthorizationResponse authorization(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerImportAuthorizationRequest request) {
        return mediaAssetService.authorizeWorkerImport((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/{jobId}/complete")
    public MediaAssetSummary complete(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerImportCompletionRequest request) {
        return mediaAssetService.completeWorkerImport((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/{jobId}/fail")
    public MediaAssetSummary fail(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerImportFailureRequest request) {
        return mediaAssetService.failWorkerImport((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }
}
