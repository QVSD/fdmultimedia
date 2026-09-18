package com.fdmultimedia.api.contentsources;

/**
 * MEDIA_LIBRARY is the only type in Phase 11D: a ContentSource whose
 * membership is exactly the workspace MediaAssets a human has explicitly
 * added to it. There is no RSS, feed, or website-crawling type — adding one
 * would require the backend to fetch arbitrary external content on its own,
 * which is explicitly out of scope.
 */
public enum ContentSourceType {
    MEDIA_LIBRARY
}
