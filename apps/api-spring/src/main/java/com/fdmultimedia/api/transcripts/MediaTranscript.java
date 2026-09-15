package com.fdmultimedia.api.transcripts;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.jobs.Job;
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
import java.util.UUID;

@Entity
@Table(name = "media_transcripts")
public class MediaTranscript {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private MediaAsset asset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TranscriptStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transcription_job_id", nullable = false)
    private Job transcriptionJob;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "detected_language")
    private String detectedLanguage;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MediaTranscript() {
    }

    public MediaTranscript(Workspace workspace, MediaAsset asset, Job transcriptionJob, String provider, String model, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.asset = asset;
        this.transcriptionJob = transcriptionJob;
        this.provider = provider;
        this.model = model;
        this.status = TranscriptStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    void prePersist() {
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = timestamp;
        }
        if (updatedAt == null) {
            updatedAt = timestamp;
        }
    }

    public void markRunning(Instant now) {
        if (status != TranscriptStatus.PENDING && status != TranscriptStatus.RUNNING) {
            throw new IllegalStateException("Transcript is not pending");
        }
        this.status = TranscriptStatus.RUNNING;
        if (startedAt == null) {
            startedAt = now;
        }
        this.errorCode = null;
        this.errorMessage = null;
        this.updatedAt = now;
    }

    public void markPendingForRetry(Instant now) {
        if (status != TranscriptStatus.RUNNING && status != TranscriptStatus.PENDING) {
            throw new IllegalStateException("Transcript cannot be retried");
        }
        this.status = TranscriptStatus.PENDING;
        this.updatedAt = now;
    }

    public void markSucceeded(String detectedLanguage, Long durationMs, Instant now) {
        if (status != TranscriptStatus.RUNNING && status != TranscriptStatus.SUCCEEDED) {
            throw new IllegalStateException("Transcript is not running");
        }
        this.status = TranscriptStatus.SUCCEEDED;
        this.detectedLanguage = normalize(detectedLanguage);
        this.durationMs = durationMs;
        this.errorCode = null;
        this.errorMessage = null;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void markFailed(String errorCode, String errorMessage, Instant now) {
        this.status = TranscriptStatus.FAILED;
        this.errorCode = normalize(errorCode);
        this.errorMessage = normalize(errorMessage);
        this.completedAt = now;
        this.updatedAt = now;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public MediaAsset getAsset() { return asset; }
    public TranscriptStatus getStatus() { return status; }
    public Job getTranscriptionJob() { return transcriptionJob; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getDetectedLanguage() { return detectedLanguage; }
    public Long getDurationMs() { return durationMs; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
