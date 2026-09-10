package com.fdmultimedia.api.jobs;

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
@RequestMapping("/api/worker-agent/jobs")
public class WorkerJobController {

    private final JobService jobService;

    public WorkerJobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/claim")
    public WorkerJobClaimResponse claim(
            Authentication authentication,
            @Valid @RequestBody WorkerJobClaimRequest request) {
        return jobService.claim((WorkerPrincipal) authentication.getPrincipal(), request);
    }

    @PostMapping("/{jobId}/started")
    public JobSummary started(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerJobUpdateRequest request) {
        return jobService.started((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/{jobId}/complete")
    public JobSummary complete(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerJobUpdateRequest request) {
        return jobService.complete((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/{jobId}/fail")
    public JobSummary fail(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerJobUpdateRequest request) {
        return jobService.fail((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }

    @PostMapping("/{jobId}/renew")
    public JobSummary renew(
            Authentication authentication,
            @PathVariable UUID jobId,
            @Valid @RequestBody WorkerJobUpdateRequest request) {
        return jobService.renew((WorkerPrincipal) authentication.getPrincipal(), jobId, request);
    }
}
