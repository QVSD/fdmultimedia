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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_worker_id")
    private Worker assignedWorker;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Job() {
    }

    public Job(Workspace workspace, JobType type, Map<String, Object> payload, int maxAttempts, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.type = type;
        this.status = JobStatus.QUEUED;
        this.payload = new LinkedHashMap<>(payload);
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.queuedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    void prePersist() {
        Instant timestamp = createdAt != null
                ? createdAt
                : queuedAt != null
                        ? queuedAt
                        : updatedAt != null
                                ? updatedAt
                                : Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (queuedAt == null) {
            queuedAt = timestamp;
        }
        if (createdAt == null) {
            createdAt = timestamp;
        }
        if (updatedAt == null) {
            updatedAt = timestamp;
        }
    }

    public void claim(Worker worker, Instant now, Instant leaseExpiresAt) {
        requireStatus(JobStatus.QUEUED);
        this.status = JobStatus.ASSIGNED;
        this.assignedWorker = worker;
        this.assignedAt = now;
        this.startedAt = null;
        this.finishedAt = null;
        this.leaseExpiresAt = leaseExpiresAt;
        this.attemptCount++;
        this.updatedAt = now;
    }

    public void start(Worker worker, Instant now, Instant leaseExpiresAt) {
        requireAssignedWorker(worker);
        requireStatus(JobStatus.ASSIGNED);
        this.status = JobStatus.RUNNING;
        this.startedAt = now;
        this.leaseExpiresAt = leaseExpiresAt;
        this.updatedAt = now;
    }

    public void complete(Worker worker, Map<String, Object> result, Instant now) {
        requireAssignedWorker(worker);
        requireStatus(JobStatus.RUNNING);
        this.status = JobStatus.SUCCEEDED;
        this.result = new LinkedHashMap<>(result);
        this.errorCode = null;
        this.errorMessage = null;
        this.finishedAt = now;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
    }

    public void renewLease(Worker worker, Instant now, Instant leaseExpiresAt) {
        requireAssignedWorker(worker);
        if (status != JobStatus.ASSIGNED && status != JobStatus.RUNNING) {
            throw new IllegalStateException("Only active jobs can renew a lease");
        }
        this.leaseExpiresAt = leaseExpiresAt;
        this.updatedAt = now;
    }

    public void fail(Worker worker, String errorCode, String errorMessage, Instant now) {
        requireAssignedWorker(worker);
        if (status != JobStatus.ASSIGNED && status != JobStatus.RUNNING) {
            throw new IllegalStateException("Only active jobs can fail");
        }
        this.errorCode = normalizeError(errorCode);
        this.errorMessage = normalizeError(errorMessage);
        this.result = null;
        this.finishedAt = now;
        this.leaseExpiresAt = null;
        if (attemptCount < maxAttempts) {
            requeue(now);
        } else {
            this.status = JobStatus.FAILED;
        }
        this.updatedAt = now;
    }

    public void failTerminal(Worker worker, String errorCode, String errorMessage, Instant now) {
        requireAssignedWorker(worker);
        if (status != JobStatus.ASSIGNED && status != JobStatus.RUNNING) {
            throw new IllegalStateException("Only active jobs can fail");
        }
        this.status = JobStatus.FAILED;
        this.errorCode = normalizeError(errorCode);
        this.errorMessage = normalizeError(errorMessage);
        this.result = null;
        this.finishedAt = now;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
    }

    public void recoverExpiredLease(Instant now) {
        if (status != JobStatus.ASSIGNED && status != JobStatus.RUNNING) {
            return;
        }
        if (leaseExpiresAt == null || leaseExpiresAt.isAfter(now)) {
            return;
        }
        this.errorCode = "LEASE_EXPIRED";
        this.errorMessage = "Worker lease expired";
        this.result = null;
        this.finishedAt = now;
        this.leaseExpiresAt = null;
        if (attemptCount < maxAttempts) {
            requeue(now);
        } else {
            this.status = JobStatus.FAILED;
        }
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        if (status.isTerminal()) {
            throw new IllegalStateException("Terminal jobs cannot be cancelled");
        }
        this.status = JobStatus.CANCELLED;
        this.finishedAt = now;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
    }

    private void requeue(Instant now) {
        this.status = JobStatus.QUEUED;
        this.assignedWorker = null;
        this.assignedAt = null;
        this.startedAt = null;
        this.finishedAt = null;
        this.queuedAt = now;
    }

    private void requireStatus(JobStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected job status " + expected + " but was " + status);
        }
    }

    private void requireAssignedWorker(Worker worker) {
        if (assignedWorker == null || !assignedWorker.getId().equals(worker.getId())) {
            throw new IllegalStateException("Job is assigned to another worker");
        }
    }

    private String normalizeError(String value) {
        if (value == null || value.isBlank()) {
            return "Worker reported failure";
        }
        return value.trim();
    }

    public UUID getId() {
        return id;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public JobType getType() {
        return type;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Worker getAssignedWorker() {
        return assignedWorker;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
