package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "highlight_selections")
public class HighlightSelection {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id") private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "media_asset_id") private MediaAsset mediaAsset;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "highlight_analysis_id") private HighlightAnalysis analysis;
    @Column(name = "selector_version", nullable = false) private String selectorVersion;
    @Column(name = "requested_count", nullable = false) private int requestedCount;
    @Column(name = "selected_count", nullable = false) private int selectedCount;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private HighlightSelectionStatus status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected HighlightSelection() {}

    public HighlightSelection(HighlightAnalysis analysis, String selectorVersion, int requestedCount,
            int selectedCount, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.workspace = analysis.getWorkspace();
        this.mediaAsset = analysis.getAsset();
        this.analysis = analysis;
        this.selectorVersion = selectorVersion;
        this.requestedCount = requestedCount;
        this.selectedCount = selectedCount;
        this.status = selectedCount == 0 ? HighlightSelectionStatus.EMPTY
                : selectedCount == requestedCount ? HighlightSelectionStatus.COMPLETE : HighlightSelectionStatus.PARTIAL;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public MediaAsset getMediaAsset() { return mediaAsset; }
    public HighlightAnalysis getAnalysis() { return analysis; }
    public String getSelectorVersion() { return selectorVersion; }
    public int getRequestedCount() { return requestedCount; }
    public int getSelectedCount() { return selectedCount; }
    public HighlightSelectionStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
