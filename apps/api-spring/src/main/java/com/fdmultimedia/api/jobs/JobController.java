package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public JobSummary create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody JobCreateRequest request) {
        return jobService.create(principal, request);
    }

    @GetMapping
    public List<JobSummary> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return jobService.listFor(principal);
    }

    @GetMapping("/{jobId}")
    public JobSummary get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID jobId) {
        return jobService.getFor(principal, jobId);
    }

    @PostMapping("/{jobId}/cancel")
    public JobSummary cancel(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID jobId) {
        return jobService.cancel(principal, jobId);
    }
}
