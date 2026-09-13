package com.fdmultimedia.api.highlights;

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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "highlight_analyses")
public class HighlightAnalysis {

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
    private HighlightAnalysisStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_job_id", nullable = false)
    private Job analysisJob;

    @Column(name = "analyzer_type", nullable = false)
    private String analyzerType;

    @Column(name = "analyzer_version", nullable = false)
    private String analyzerVersion;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "analysis")
    @OrderBy("rank ASC")
    private List<HighlightCandidate> candidates = new ArrayList<>();

    protected HighlightAnalysis() {
    }

    public HighlightAnalysis(Workspace workspace, MediaAsset asset, Job analysisJob, String analyzerType, String analyzerVersion, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.asset = asset;
        this.status = HighlightAnalysisStatus.PENDING;
        this.analysisJob = analysisJob;
        this.analyzerType = analyzerType;
        this.analyzerVersion = analyzerVersion;
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
        if (status != HighlightAnalysisStatus.PENDING && status != HighlightAnalysisStatus.RUNNING) {
            throw new IllegalStateException("Analysis is not pending");
        }
        status = HighlightAnalysisStatus.RUNNING;
        errorCode = null;
        errorMessage = null;
        updatedAt = now;
    }

    public void markPendingForRetry(Instant now) {
        if (status == HighlightAnalysisStatus.SUCCEEDED || status == HighlightAnalysisStatus.FAILED) {
            throw new IllegalStateException("Terminal analysis cannot be retried");
        }
        status = HighlightAnalysisStatus.PENDING;
        updatedAt = now;
    }

    public void markSucceeded(Instant now) {
        if (status != HighlightAnalysisStatus.RUNNING && status != HighlightAnalysisStatus.PENDING) {
            throw new IllegalStateException("Analysis is not active");
        }
        status = HighlightAnalysisStatus.SUCCEEDED;
        errorCode = null;
        errorMessage = null;
        completedAt = now;
        updatedAt = now;
    }

    public void markFailed(String errorCode, String errorMessage, Instant now) {
        if (status == HighlightAnalysisStatus.SUCCEEDED) {
            throw new IllegalStateException("Succeeded analysis cannot fail");
        }
        status = HighlightAnalysisStatus.FAILED;
        this.errorCode = normalize(errorCode);
        this.errorMessage = normalize(errorMessage);
        completedAt = now;
        updatedAt = now;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "Highlight analysis failed" : value.trim();
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public MediaAsset getAsset() { return asset; }
    public HighlightAnalysisStatus getStatus() { return status; }
    public Job getAnalysisJob() { return analysisJob; }
    public String getAnalyzerType() { return analyzerType; }
    public String getAnalyzerVersion() { return analyzerVersion; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public List<HighlightCandidate> getCandidates() { return candidates; }
}
