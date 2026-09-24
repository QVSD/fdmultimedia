package com.fdmultimedia.api.highlights;

public record WorkerHighlightTranscriptSegmentResponse(
        java.util.UUID id,
        int sequence,
        long startMs,
        long endMs,
        String text) {
    public WorkerHighlightTranscriptSegmentResponse(int sequence, long startMs, long endMs, String text) {
        this(null, sequence, startMs, endMs, text);
    }
}
