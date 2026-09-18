package com.fdmultimedia.api.contentdrafts;

/**
 * Durable orchestration progress, independent of {@link ContentDraftStatus}.
 * CLIP_PENDING/VERTICAL_PENDING drafts are waiting on the Job referenced by
 * {@code pendingJobId}; the underlying MediaAsset's own status is the source
 * of truth reconciliation reads, never a cached in-memory callback.
 */
public enum ContentDraftWorkflowStage {
    CLIP_PENDING,
    VERTICAL_PENDING,
    READY
}
