package com.fdmultimedia.worker;

record HighlightTranscriptSegment(
        java.util.UUID id,
        int sequence,
        long startMs,
        long endMs,
        String text) {
    HighlightTranscriptSegment(int sequence, long startMs, long endMs, String text) {
        this(null, sequence, startMs, endMs, text);
    }
}
