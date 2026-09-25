package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.jobs.Job;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "highlight_selection_items")
public class HighlightSelectionItem {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "selection_id") private HighlightSelection selection;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "highlight_candidate_id") private HighlightCandidate candidate;
    @Column(name = "selection_order", nullable = false) private int selectionOrder;
    @Column(name = "source_rank", nullable = false) private int sourceRank;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "clip_asset_id") private MediaAsset clipAsset;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "clip_job_id") private Job clipJob;
    @Column(name = "clip_request_failure_code") private String clipRequestFailureCode;
    @Column(name = "clip_request_failure_message") private String clipRequestFailureMessage;

    protected HighlightSelectionItem() {}
    public HighlightSelectionItem(HighlightSelection selection, HighlightCandidate candidate, int selectionOrder) {
        this.id = UUID.randomUUID(); this.selection = selection; this.candidate = candidate;
        this.selectionOrder = selectionOrder; this.sourceRank = candidate.getRank();
    }
    public void attachClip(MediaAsset asset, Job job) {
        if (clipAsset != null) return;
        clipAsset = asset; clipJob = job; clipRequestFailureCode = null; clipRequestFailureMessage = null;
    }
    public void recordClipRequestFailure(String code, String message) {
        clipRequestFailureCode = code;
        clipRequestFailureMessage = message == null ? null : message.substring(0, Math.min(500, message.length()));
    }
    public UUID getId() { return id; }
    public HighlightSelection getSelection() { return selection; }
    public HighlightCandidate getCandidate() { return candidate; }
    public int getSelectionOrder() { return selectionOrder; }
    public int getSourceRank() { return sourceRank; }
    public MediaAsset getClipAsset() { return clipAsset; }
    public Job getClipJob() { return clipJob; }
    public String getClipRequestFailureCode() { return clipRequestFailureCode; }
    public String getClipRequestFailureMessage() { return clipRequestFailureMessage; }
}
