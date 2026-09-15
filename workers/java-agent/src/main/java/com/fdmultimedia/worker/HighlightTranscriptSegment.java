package com.fdmultimedia.worker;

record HighlightTranscriptSegment(
        int sequence,
        long startMs,
        long endMs,
        String text) {
}
