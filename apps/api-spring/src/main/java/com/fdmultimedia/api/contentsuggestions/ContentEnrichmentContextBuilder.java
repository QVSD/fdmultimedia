package com.fdmultimedia.api.contentsuggestions;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.highlights.HighlightCandidate;
import com.fdmultimedia.api.transcripts.MediaTranscript;
import com.fdmultimedia.api.transcripts.MediaTranscriptRepository;
import com.fdmultimedia.api.transcripts.TranscriptSegment;
import com.fdmultimedia.api.transcripts.TranscriptSegmentRepository;
import com.fdmultimedia.api.transcripts.TranscriptStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Gathers only authorized, bounded context for one Draft — never entire DB
 * entities, never an unbounded transcript. Strong preference: when the Draft
 * came from a {@link HighlightCandidate}, use only the transcript segments
 * overlapping that candidate's own time range (the actual selected clip),
 * padded by {@code transcriptContextPaddingMs} on each side, not the whole
 * source transcript. When there is no candidate (a Draft created directly
 * from an existing asset), there is no highlight window to overlap against,
 * so a bounded leading prefix of the transcript is used instead — this is
 * intentionally a weaker fallback, and {@code transcriptUsed}/context size
 * are always reported honestly rather than pretending otherwise.
 */
@Component
public class ContentEnrichmentContextBuilder {

    private final MediaTranscriptRepository transcripts;
    private final TranscriptSegmentRepository transcriptSegments;
    private final ContentAiProperties properties;

    public ContentEnrichmentContextBuilder(
            MediaTranscriptRepository transcripts, TranscriptSegmentRepository transcriptSegments, ContentAiProperties properties) {
        this.transcripts = transcripts;
        this.transcriptSegments = transcriptSegments;
        this.properties = properties;
    }

    public ContentEnrichmentContext build(ContentDraft draft) {
        MediaAsset sourceAsset = draft.getSourceAsset();
        HighlightCandidate candidate = draft.getSourceHighlightCandidate();
        TranscriptContext transcriptContext = resolveTranscriptContext(sourceAsset, candidate);
        return new ContentEnrichmentContext(
                draft.getId(),
                draft.getTitle(),
                draft.getCaption(),
                sourceAsset.getOriginalFilename(),
                sourceAsset.getDurationMs(),
                candidate == null ? null : candidate.getReason(),
                candidate == null ? null : candidate.getScore().toPlainString(),
                candidate == null ? null : candidate.getStartMs(),
                candidate == null ? null : candidate.getEndMs(),
                transcriptContext.used(),
                transcriptContext.transcriptId(),
                transcriptContext.excerpt(),
                transcriptContext.segmentCount());
    }

    private TranscriptContext resolveTranscriptContext(MediaAsset sourceAsset, HighlightCandidate candidate) {
        MediaTranscript transcript = transcripts.findByWorkspaceAndAssetOrderByCreatedAtDesc(sourceAsset.getWorkspace(), sourceAsset).stream()
                .filter(candidateTranscript -> candidateTranscript.getStatus() == TranscriptStatus.SUCCEEDED)
                .findFirst()
                .orElse(null);
        if (transcript == null) {
            return TranscriptContext.unused();
        }
        List<TranscriptSegment> segments = transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript);
        if (segments.isEmpty()) {
            return TranscriptContext.unused();
        }
        List<TranscriptSegment> selected = candidate != null
                ? overlapping(segments, candidate.getStartMs(), candidate.getEndMs())
                : segments;
        if (selected.isEmpty()) {
            // The candidate window did not land on any transcript segment
            // (e.g. a silent portion) — fall back to a bounded prefix rather
            // than reporting no transcript context at all.
            selected = segments;
        }
        int maxSegments = properties.getMaxTranscriptContextSegments();
        if (selected.size() > maxSegments) {
            selected = selected.subList(0, maxSegments);
        }
        String excerpt = joinBounded(selected, properties.getMaxTranscriptContextCharacters());
        return new TranscriptContext(true, transcript.getId(), excerpt, selected.size());
    }

    private List<TranscriptSegment> overlapping(List<TranscriptSegment> segments, long startMs, long endMs) {
        long paddedStart = Math.max(0, startMs - properties.getTranscriptContextPaddingMs());
        long paddedEnd = endMs + properties.getTranscriptContextPaddingMs();
        return segments.stream()
                .filter(segment -> segment.getStartMs() < paddedEnd && segment.getEndMs() > paddedStart)
                .toList();
    }

    /** Bounded by character count — never a mid-thought silent truncation of the final segment; a whole segment is either included or dropped. */
    private String joinBounded(List<TranscriptSegment> segments, int maxCharacters) {
        StringBuilder builder = new StringBuilder();
        for (TranscriptSegment segment : segments) {
            String text = segment.getText() == null ? "" : segment.getText().trim();
            if (text.isEmpty()) {
                continue;
            }
            int prospectiveLength = builder.length() + (builder.isEmpty() ? 0 : 1) + text.length();
            if (prospectiveLength > maxCharacters) {
                break;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(text);
        }
        return builder.toString();
    }

    private record TranscriptContext(boolean used, UUID transcriptId, String excerpt, int segmentCount) {
        static TranscriptContext unused() {
            return new TranscriptContext(false, null, null, 0);
        }
    }
}
