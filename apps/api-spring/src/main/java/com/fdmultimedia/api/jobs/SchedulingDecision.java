package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "scheduling_decisions")
public class SchedulingDecision {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType;

    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false)
    private String policy;

    @Column(nullable = false)
    private String decision;

    @Column(name = "suitability_score")
    private Double suitabilityScore;

    @Column(name = "telemetry_fresh", nullable = false)
    private boolean telemetryFresh;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(name = "starvation_override", nullable = false)
    private boolean starvationOverride;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reason_codes", nullable = false, columnDefinition = "jsonb")
    private List<String> reasonCodes;

    @Column(name = "active_jobs")
    private Integer activeJobs;

    @Column(name = "max_active_jobs", nullable = false)
    private int maxActiveJobs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SchedulingDecision() {
    }

    public SchedulingDecision(Job job, Worker worker, JobSchedulingDecision decision, String policy, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.workspace = job.getWorkspace();
        this.job = job;
        this.worker = worker;
        this.jobType = job.getType();
        this.attempt = job.getAttemptCount();
        this.policy = policy;
        this.decision = "CLAIMED";
        this.suitabilityScore = decision.suitabilityScore();
        this.telemetryFresh = decision.telemetryFresh();
        this.fallbackUsed = decision.fallbackUsed();
        this.starvationOverride = decision.starvationOverride();
        this.reasonCodes = List.copyOf(decision.reasonCodes());
        this.activeJobs = worker.getActiveJobs();
        this.maxActiveJobs = worker.getMaxActiveJobs();
        this.createdAt = createdAt;
    }

    public Job getJob() { return job; }
    public Worker getWorker() { return worker; }
    public int getAttempt() { return attempt; }
    public String getPolicy() { return policy; }
    public Double getSuitabilityScore() { return suitabilityScore; }
    public boolean isTelemetryFresh() { return telemetryFresh; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public boolean isStarvationOverride() { return starvationOverride; }
    public List<String> getReasonCodes() { return reasonCodes; }
}
