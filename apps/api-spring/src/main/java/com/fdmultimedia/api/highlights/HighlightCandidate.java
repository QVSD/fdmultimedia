package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "highlight_candidates")
public class HighlightCandidate {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false)
    private HighlightAnalysis analysis;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private MediaAsset asset;

    @Column(name = "start_ms", nullable = false)
    private long startMs;

    @Column(name = "end_ms", nullable = false)
    private long endMs;

    @Column(nullable = false)
    private BigDecimal score;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private int rank;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected HighlightCandidate() {
    }

    public HighlightCandidate(HighlightAnalysis analysis, long startMs, long endMs, BigDecimal score, String reason, int rank, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = analysis.getWorkspace();
        this.analysis = analysis;
        this.asset = analysis.getAsset();
        this.startMs = startMs;
        this.endMs = endMs;
        this.score = score;
        this.reason = reason;
        this.rank = rank;
        this.createdAt = now;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public HighlightAnalysis getAnalysis() { return analysis; }
    public MediaAsset getAsset() { return asset; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public BigDecimal getScore() { return score; }
    public String getReason() { return reason; }
    public int getRank() { return rank; }
    public Instant getCreatedAt() { return createdAt; }
}
