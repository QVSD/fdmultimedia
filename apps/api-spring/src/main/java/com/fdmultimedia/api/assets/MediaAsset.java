package com.fdmultimedia.api.assets;

import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.users.AppUser;
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
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private MediaAssetSourceType sourceType;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaAssetStatus status;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "checksum_sha256")
    private String checksumSha256;

    @Column(name = "storage_bucket")
    private String storageBucket;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "duration_ms")
    private Long durationMs;

    private Integer width;

    private Integer height;

    @Column(name = "video_codec")
    private String videoCodec;

    @Column(name = "audio_codec")
    private String audioCodec;

    @Column(name = "container_format")
    private String containerFormat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private AppUser createdByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_job_id")
    private Job importJob;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    protected MediaAsset() {
    }

    public MediaAsset(Workspace workspace, AppUser createdByUser, String sourceUrl, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.createdByUser = createdByUser;
        this.sourceType = MediaAssetSourceType.DIRECT_URL;
        this.sourceUrl = sourceUrl;
        this.status = MediaAssetStatus.PENDING;
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

    public void attachImportJob(Job job, Instant now) {
        this.importJob = job;
        this.updatedAt = now;
    }

    public void markImporting(Instant now) {
        if (status != MediaAssetStatus.PENDING && status != MediaAssetStatus.IMPORTING) {
            throw new IllegalStateException("Only pending assets can start importing");
        }
        this.status = MediaAssetStatus.IMPORTING;
        this.errorCode = null;
        this.errorMessage = null;
        this.updatedAt = now;
    }

    public void markPendingForRetry(Instant now) {
        if (status != MediaAssetStatus.IMPORTING && status != MediaAssetStatus.PENDING) {
            throw new IllegalStateException("Only active assets can be retried");
        }
        this.status = MediaAssetStatus.PENDING;
        this.updatedAt = now;
    }

    public void markReady(MediaImportMetadata metadata, String bucket, String key, Instant now) {
        if (status != MediaAssetStatus.IMPORTING && status != MediaAssetStatus.READY) {
            throw new IllegalStateException("Only importing assets can become ready");
        }
        this.status = MediaAssetStatus.READY;
        this.originalFilename = metadata.originalFilename();
        this.contentType = metadata.contentType();
        this.fileSizeBytes = metadata.fileSizeBytes();
        this.checksumSha256 = metadata.checksumSha256();
        this.durationMs = metadata.durationMs();
        this.width = metadata.width();
        this.height = metadata.height();
        this.videoCodec = metadata.videoCodec();
        this.audioCodec = metadata.audioCodec();
        this.containerFormat = metadata.containerFormat();
        this.storageBucket = bucket;
        this.storageKey = key;
        this.errorCode = null;
        this.errorMessage = null;
        this.readyAt = now;
        this.updatedAt = now;
    }

    public void markFailed(String errorCode, String errorMessage, Instant now) {
        if (status == MediaAssetStatus.READY) {
            throw new IllegalStateException("Ready assets cannot fail");
        }
        this.status = MediaAssetStatus.FAILED;
        this.errorCode = normalize(errorCode);
        this.errorMessage = normalize(errorMessage);
        this.updatedAt = now;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "Import failed" : value.trim();
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public MediaAssetSourceType getSourceType() { return sourceType; }
    public String getSourceUrl() { return sourceUrl; }
    public MediaAssetStatus getStatus() { return status; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public String getChecksumSha256() { return checksumSha256; }
    public String getStorageBucket() { return storageBucket; }
    public String getStorageKey() { return storageKey; }
    public Long getDurationMs() { return durationMs; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getVideoCodec() { return videoCodec; }
    public String getAudioCodec() { return audioCodec; }
    public String getContainerFormat() { return containerFormat; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Job getImportJob() { return importJob; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getReadyAt() { return readyAt; }
}
