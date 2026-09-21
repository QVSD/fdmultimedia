package com.fdmultimedia.api.publishing;

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
@RequestMapping("/api/worker-agent/publications")
public class WorkerPublishingController {

    private final PublishingService publishingService;

    public WorkerPublishingController(PublishingService publishingService) {
        this.publishingService = publishingService;
    }

    @PostMapping("/{jobId}/authorization")
    public WorkerPublicationAuthorizationResponse authorization(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerPublicationAuthorizationRequest request) {
        return publishingService.authorizeWorkerPublication(
                (WorkerPrincipal) authentication.getPrincipal(), jobId, request.machineIdentifier());
    }

    @PostMapping("/{jobId}/complete")
    public PublicationSummary complete(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerPublicationCompletionRequest request) {
        return publishingService.completeWorkerPublication((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/{jobId}/fail")
    public PublicationSummary fail(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerPublicationFailureRequest request) {
        return publishingService.failWorkerPublication((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/{jobId}/instagram/drive")
    public PublicationDriveResponse driveInstagram(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerPublicationAuthorizationRequest request) {
        return publishingService.driveInstagramPublication(
                (WorkerPrincipal) authentication.getPrincipal(), jobId, request.machineIdentifier());
    }

    @PostMapping("/{jobId}/tiktok/drive")
    public PublicationDriveResponse driveTikTok(Authentication authentication, @PathVariable UUID jobId,
            @Valid @RequestBody WorkerPublicationAuthorizationRequest request) {
        return publishingService.driveTikTokPublication((WorkerPrincipal) authentication.getPrincipal(), jobId, request.machineIdentifier());
    }
}
