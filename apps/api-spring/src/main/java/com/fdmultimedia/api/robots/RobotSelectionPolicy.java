package com.fdmultimedia.api.robots;

/**
 * Deterministic, explainable ordering over a ContentSource's membership —
 * never AI ranking, relevance scoring, or prediction of any kind. Ordering
 * is by {@code content_source_assets.added_at} (when the asset joined this
 * specific source), not the MediaAsset's own creation time, with the
 * membership row id as a stable tie-breaker when two additions share a
 * timestamp.
 */
public enum RobotSelectionPolicy {
    OLDEST_UNPROCESSED,
    NEWEST_UNPROCESSED
}
