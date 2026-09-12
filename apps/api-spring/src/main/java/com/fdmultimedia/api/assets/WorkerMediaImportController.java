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
@RequestMapping("/api/worker-agent/assets")
public class WorkerMediaImportController {

    private final MediaAssetService mediaAssetService;

    public WorkerMediaImportController(MediaAssetService mediaAssetService) {
        this.mediaAssetService = mediaAssetService;
    }

    @PostMapping("/imports/{jobId}/authorization")
    public WorkerImportAuthorizationResponse authorization(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerImportAuthorizationRequest request) {
        return mediaAssetService.authorizeWorkerImport((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/imports/{jobId}/complete")
    public MediaAssetSummary complete(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerImportCompletionRequest request) {
        return mediaAssetService.completeWorkerImport((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/imports/{jobId}/fail")
    public MediaAssetSummary fail(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerImportFailureRequest request) {
        return mediaAssetService.failWorkerImport((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/inspections/{jobId}/authorization")
    public WorkerInspectionAuthorizationResponse inspectionAuthorization(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerImportAuthorizationRequest request) {
        return mediaAssetService.authorizeWorkerInspection((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/inspections/{jobId}/complete")
    public MediaAssetSummary completeInspection(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerInspectionCompletionRequest request) {
        return mediaAssetService.completeWorkerInspection((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/inspections/{jobId}/fail")
    public MediaAssetSummary failInspection(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerInspectionFailureRequest request) {
        return mediaAssetService.failWorkerInspection((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/clips/{jobId}/authorization")
    public WorkerClipAuthorizationResponse clipAuthorization(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerImportAuthorizationRequest request) {
        return mediaAssetService.authorizeWorkerClip((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/clips/{jobId}/complete")
    public MediaAssetSummary completeClip(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerClipCompletionRequest request) {
        return mediaAssetService.completeWorkerClip((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/clips/{jobId}/fail")
    public MediaAssetSummary failClip(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerClipFailureRequest request) {
        return mediaAssetService.failWorkerClip((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }
}
