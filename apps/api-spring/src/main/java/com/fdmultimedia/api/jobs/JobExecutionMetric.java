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
import java.util.UUID;

@Entity
@Table(name = "job_execution_metrics")
public class JobExecutionMetric {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_id")
    private Worker worker;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobExecutionOutcome outcome;

    @Column(name = "queue_wait_ms")
    private Long queueWaitMs;

    @Column(name = "execution_ms")
    private Long executionMs;

    @Column(name = "total_latency_ms")
    private Long totalLatencyMs;

    @Column(name = "workload_size_bytes")
    private Long workloadSizeBytes;

    @Column(name = "workload_duration_ms")
    private Long workloadDurationMs;

    @Column(name = "workload_width")
    private Integer workloadWidth;

    @Column(name = "workload_height")
    private Integer workloadHeight;

    private String provider;

    private String model;

    @Column(name = "analyzer_type")
    private String analyzerType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JobExecutionMetric() {
    }

    public JobExecutionMetric(
            Job job,
            Worker worker,
            int attempt,
            JobExecutionOutcome outcome,
            Long queueWaitMs,
            Long executionMs,
            Long totalLatencyMs,
            WorkloadHints hints,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.job = job;
        this.workspace = job.getWorkspace();
        this.worker = worker;
        this.jobType = job.getType();
        this.attempt = attempt;
        this.outcome = outcome;
        this.queueWaitMs = queueWaitMs;
        this.executionMs = executionMs;
        this.totalLatencyMs = totalLatencyMs;
        this.workloadSizeBytes = hints.sizeBytes();
        this.workloadDurationMs = hints.durationMs();
        this.workloadWidth = hints.width();
        this.workloadHeight = hints.height();
        this.provider = hints.provider();
        this.model = hints.model();
        this.analyzerType = hints.analyzerType();
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Job getJob() {
        return job;
    }

    public Worker getWorker() {
        return worker;
    }

    public JobType getJobType() {
        return jobType;
    }

    public int getAttempt() {
        return attempt;
    }

    public JobExecutionOutcome getOutcome() {
        return outcome;
    }

    public Long getQueueWaitMs() {
        return queueWaitMs;
    }

    public Long getExecutionMs() {
        return executionMs;
    }

    public Long getTotalLatencyMs() {
        return totalLatencyMs;
    }
}
