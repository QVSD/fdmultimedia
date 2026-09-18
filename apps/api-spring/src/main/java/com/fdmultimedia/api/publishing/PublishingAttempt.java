package com.fdmultimedia.api.publishing;

import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.workers.Worker;
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

/**
 * Per-attempt publishing history, one row per real Job attempt that was
 * actually reported by a Worker (success or failure). Never overwritten on
 * retry: a new attempt number creates a new row, so "attempt 1 failed,
 * attempt 2 succeeded" remains visible after the Publication reaches its
 * final state.
 */
@Entity
@Table(name = "publishing_attempts")
public class PublishingAttempt {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publication_id", nullable = false)
    private Publication publication;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(name = "job_attempt", nullable = false)
    private int jobAttempt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_id")
    private Worker worker;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PublishingAttemptOutcome outcome;

    @Column(name = "provider_request_id")
    private String providerRequestId;

    @Column(name = "provider_publication_id")
    private String providerPublicationId;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PublishingAttempt() {
    }

    public PublishingAttempt(
            Publication publication,
            Job job,
            int jobAttempt,
            Worker worker,
            Instant startedAt,
            Instant finishedAt,
            PublishingAttemptOutcome outcome,
            String providerRequestId,
            String providerPublicationId,
            String errorCode,
            String errorMessage) {
        this.id = UUID.randomUUID();
        this.publication = publication;
        this.job = job;
        this.jobAttempt = jobAttempt;
        this.worker = worker;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.outcome = outcome;
        this.providerRequestId = providerRequestId;
        this.providerPublicationId = providerPublicationId;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.createdAt = finishedAt;
    }

    public UUID getId() { return id; }
    public Publication getPublication() { return publication; }
    public Job getJob() { return job; }
    public int getJobAttempt() { return jobAttempt; }
    public Worker getWorker() { return worker; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public PublishingAttemptOutcome getOutcome() { return outcome; }
    public String getProviderRequestId() { return providerRequestId; }
    public String getProviderPublicationId() { return providerPublicationId; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
