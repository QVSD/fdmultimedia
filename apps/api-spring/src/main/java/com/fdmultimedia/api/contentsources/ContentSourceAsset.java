package com.fdmultimedia.api.contentsources;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.users.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable, explicit membership of one MediaAsset in one ContentSource.
 * {@code addedAt} defines the ordering OLDEST_UNPROCESSED/NEWEST_UNPROCESSED
 * select over — deliberately membership time, not the asset's own creation
 * time, so re-adding an older asset to a source later still queues it
 * behind assets added earlier. Removing membership never touches the
 * MediaAsset itself or any RobotRun that already selected it.
 */
@Entity
@Table(name = "content_source_assets")
public class ContentSourceAsset {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_source_id", nullable = false)
    private ContentSource contentSource;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_asset_id", nullable = false)
    private MediaAsset mediaAsset;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "added_by_user_id", nullable = false)
    private AppUser addedByUser;

    protected ContentSourceAsset() {
    }

    public ContentSourceAsset(ContentSource contentSource, MediaAsset mediaAsset, AppUser addedByUser, Instant now) {
        this.id = UUID.randomUUID();
        this.contentSource = contentSource;
        this.mediaAsset = mediaAsset;
        this.addedByUser = addedByUser;
        this.addedAt = now;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (addedAt == null) {
            addedAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public ContentSource getContentSource() { return contentSource; }
    public MediaAsset getMediaAsset() { return mediaAsset; }
    public Instant getAddedAt() { return addedAt; }
    public AppUser getAddedByUser() { return addedByUser; }
}
