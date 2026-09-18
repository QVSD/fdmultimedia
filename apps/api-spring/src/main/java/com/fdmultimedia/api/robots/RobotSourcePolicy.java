package com.fdmultimedia.api.robots;

/**
 * EXISTING_ASSET: the Phase 11C behavior, unchanged — a Robot permanently
 * bound to one fixed MediaAsset (sourceAssetId).
 * CONTENT_SOURCE: Phase 11D — a Robot bound to a ContentSource plus a
 * {@link RobotSelectionPolicy}; each run deterministically selects the next
 * eligible, not-yet-consumed-by-this-Robot asset from that source instead
 * of always pointing at the same one.
 * Exactly one of (sourceAssetId) / (contentSourceId + selectionPolicy) is
 * set, enforced by both this Robot's own invariants and a database CHECK
 * constraint (see V19).
 */
public enum RobotSourcePolicy {
    EXISTING_ASSET,
    CONTENT_SOURCE
}
