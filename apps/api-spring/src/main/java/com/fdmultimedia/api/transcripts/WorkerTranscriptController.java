package com.fdmultimedia.api.transcripts;

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
@RequestMapping("/api/worker-agent/transcripts")
public class WorkerTranscriptController {

    private final TranscriptService transcriptService;

    public WorkerTranscriptController(TranscriptService transcriptService) {
        this.transcriptService = transcriptService;
    }

    @PostMapping("/{jobId}/authorization")
    public WorkerTranscriptAuthorizationResponse authorization(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerImportAuthorizationRequest request) {
        return transcriptService.authorizeWorkerTranscription(
                (WorkerPrincipal) authentication.getPrincipal(),
                jobId,
                request.machineIdentifier());
    }

    @PostMapping("/{jobId}/complete")
    public MediaTranscriptSummary complete(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerTranscriptCompletionRequest request) {
        return transcriptService.completeWorkerTranscription((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/{jobId}/fail")
    public MediaTranscriptSummary fail(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerTranscriptFailureRequest request) {
        return transcriptService.failWorkerTranscription((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }
}
