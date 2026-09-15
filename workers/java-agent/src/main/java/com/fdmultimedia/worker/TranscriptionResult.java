package com.fdmultimedia.worker;

import java.util.List;

record TranscriptionResult(
        String detectedLanguage,
        Long durationMs,
        List<TranscriptSegmentResult> segments) {
}
