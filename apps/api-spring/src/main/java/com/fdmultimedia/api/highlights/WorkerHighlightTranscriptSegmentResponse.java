package com.fdmultimedia.api.highlights;

public record WorkerHighlightTranscriptSegmentResponse(
        int sequence,
        long startMs,
        long endMs,
        String text) {
}
