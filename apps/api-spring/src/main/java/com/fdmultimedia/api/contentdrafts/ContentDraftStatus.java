package com.fdmultimedia.api.contentdrafts;

/**
 * DRAFT: preparation (clip/vertical derivation) is still in progress.
 * READY: the selected media asset is READY+INSPECTED+video; publishable.
 * PUBLISHING: at least one linked Publication is PENDING or PUBLISHING.
 * PUBLISHED: at least one linked Publication reached PUBLISHED.
 * FAILED: preparation hit a terminal derivative failure; not publishable
 * until a user-triggered retry succeeds. A failed Publication never lands
 * here — it reverts the draft back to READY instead, so it stays usable.
 */
public enum ContentDraftStatus {
    DRAFT,
    READY,
    PUBLISHING,
    PUBLISHED,
    FAILED
}
