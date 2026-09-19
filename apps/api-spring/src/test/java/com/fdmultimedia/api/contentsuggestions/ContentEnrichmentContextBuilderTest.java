package com.fdmultimedia.api.contentsuggestions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.highlights.HighlightAnalysis;
import com.fdmultimedia.api.highlights.HighlightCandidate;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.transcripts.MediaTranscript;
import com.fdmultimedia.api.transcripts.MediaTranscriptRepository;
import com.fdmultimedia.api.transcripts.TranscriptSegment;
import com.fdmultimedia.api.transcripts.TranscriptSegmentRepository;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContentEnrichmentContextBuilderTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    private final MediaTranscriptRepository transcripts = mock(MediaTranscriptRepository.class);
    private final TranscriptSegmentRepository transcriptSegments = mock(TranscriptSegmentRepository.class);
    private final ContentAiProperties properties = new ContentAiProperties();
    private final ContentEnrichmentContextBuilder builder = new ContentEnrichmentContextBuilder(transcripts, transcriptSegments, properties);

    private Workspace workspace;
    private AppUser owner;
    private MediaAsset source;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        source = readyAsset();
        when(transcripts.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, source)).thenReturn(List.of());
    }

    @Test
    void usesOnlySegmentsOverlappingTheHighlightWindowWithPadding() {
        MediaTranscript transcript = succeededTranscript();
        HighlightCandidate candidate = candidate(10_000, 15_000);
        ContentDraft draft = ContentDraft.fromHighlightCandidate(workspace, candidate, source, new Job(workspace, JobType.CREATE_CLIP, Map.of(), 3, NOW), owner, NOW);
        when(transcripts.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, source)).thenReturn(List.of(transcript));
        when(transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript)).thenReturn(List.of(
                new TranscriptSegment(transcript, 0, 0, 5_000, "Far before, should be excluded.", null, NOW),
                new TranscriptSegment(transcript, 1, 9_000, 12_000, "Overlaps the start of the window.", null, NOW),
                new TranscriptSegment(transcript, 2, 12_000, 16_000, "Inside the window.", null, NOW),
                new TranscriptSegment(transcript, 3, 25_000, 30_000, "Far after, should be excluded.", null, NOW)));

        ContentEnrichmentContext context = builder.build(draft);

        assertThat(context.transcriptUsed()).isTrue();
        assertThat(context.transcriptExcerpt()).contains("Overlaps the start of the window.", "Inside the window.");
        assertThat(context.transcriptExcerpt()).doesNotContain("Far before", "Far after");
        assertThat(context.transcriptSegmentCount()).isEqualTo(2);
        assertThat(context.highlightStartMs()).isEqualTo(10_000L);
        assertThat(context.highlightEndMs()).isEqualTo(15_000L);
    }

    @Test
    void boundsExcerptByMaxCharacters() {
        properties.setMaxTranscriptContextCharacters(10);
        MediaTranscript transcript = succeededTranscript();
        HighlightCandidate candidate = candidate(0, 5_000);
        ContentDraft draft = ContentDraft.fromHighlightCandidate(workspace, candidate, source, new Job(workspace, JobType.CREATE_CLIP, Map.of(), 3, NOW), owner, NOW);
        when(transcripts.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, source)).thenReturn(List.of(transcript));
        when(transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript)).thenReturn(List.of(
                new TranscriptSegment(transcript, 0, 0, 2_000, "This segment is definitely longer than ten characters.", null, NOW)));

        ContentEnrichmentContext context = builder.build(draft);

        assertThat(context.transcriptExcerpt()).isEmpty();
    }

    @Test
    void fallsBackToBoundedPrefixWithoutAHighlightCandidate() {
        MediaTranscript transcript = succeededTranscript();
        ContentDraft draft = ContentDraft.fromExistingAsset(workspace, source, null, null, owner, NOW);
        when(transcripts.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, source)).thenReturn(List.of(transcript));
        when(transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript)).thenReturn(List.of(
                new TranscriptSegment(transcript, 0, 0, 5_000, "Opening segment.", null, NOW),
                new TranscriptSegment(transcript, 1, 5_000, 10_000, "Second segment.", null, NOW)));

        ContentEnrichmentContext context = builder.build(draft);

        assertThat(context.transcriptUsed()).isTrue();
        assertThat(context.transcriptExcerpt()).contains("Opening segment.", "Second segment.");
        assertThat(context.highlightReason()).isNull();
    }

    @Test
    void reportsNoTranscriptHonestlyWhenNoneSucceeded() {
        ContentDraft draft = ContentDraft.fromExistingAsset(workspace, source, null, null, owner, NOW);

        ContentEnrichmentContext context = builder.build(draft);

        assertThat(context.transcriptUsed()).isFalse();
        assertThat(context.transcriptExcerpt()).isNull();
        assertThat(context.transcriptId()).isNull();
    }

    @Test
    void carriesHighlightReasonAndScoreWhenCandidatePresent() {
        HighlightCandidate candidate = candidate(1_000, 4_000);
        ContentDraft draft = ContentDraft.fromHighlightCandidate(workspace, candidate, source, new Job(workspace, JobType.CREATE_CLIP, Map.of(), 3, NOW), owner, NOW);

        ContentEnrichmentContext context = builder.build(draft);

        assertThat(context.highlightReason()).isEqualTo("Energetic moment");
        assertThat(context.highlightScore()).isEqualTo("0.9000");
    }

    @Test
    void carriesExistingDraftCaptionAsContext() {
        ContentDraft draft = ContentDraft.fromExistingAsset(workspace, source, "My title", "My existing caption", owner, NOW);

        ContentEnrichmentContext context = builder.build(draft);

        assertThat(context.draftTitle()).isEqualTo("My title");
        assertThat(context.draftCaption()).isEqualTo("My existing caption");
        assertThat(context.sourceAssetFilename()).isEqualTo("media.mp4");
        assertThat(context.sourceAssetDurationMs()).isEqualTo(20_000L);
    }

    private HighlightCandidate candidate(long startMs, long endMs) {
        Job analysisJob = new Job(workspace, JobType.ANALYZE_HIGHLIGHTS, Map.of(), 3, NOW);
        HighlightAnalysis analysis = new HighlightAnalysis(workspace, source, analysisJob, "DETERMINISTIC_V1", "v1", NOW);
        analysis.markRunning(NOW);
        analysis.markSucceeded(NOW);
        return new HighlightCandidate(analysis, startMs, endMs, new BigDecimal("0.9000"), "Energetic moment", 1, NOW);
    }

    private MediaTranscript succeededTranscript() {
        Job transcriptionJob = new Job(workspace, JobType.TRANSCRIBE_MEDIA, Map.of(), 3, NOW);
        MediaTranscript transcript = new MediaTranscript(workspace, source, transcriptionJob, "WHISPER_CLI", "base", NOW);
        transcript.markRunning(NOW);
        transcript.markSucceeded("en", 20_000L, NOW);
        return transcript;
    }

    private MediaAsset readyAsset() {
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        asset.markImporting(NOW.minusSeconds(5));
        asset.markReady(new MediaImportMetadata("media.mp4", "video/mp4", 12_000, "0".repeat(64), 20_000L, 1920, 1080, "h264", "aac", "mp4"),
                "media-assets", "storage-key", NOW.minusSeconds(4));
        Job inspectionJob = new Job(workspace, JobType.INSPECT_MEDIA, Map.of("assetId", asset.getId().toString()), 3, NOW.minusSeconds(3));
        asset.attachInspectionJob(inspectionJob, NOW.minusSeconds(2));
        asset.markInspecting(NOW.minusSeconds(1));
        asset.markInspected(new MediaInspectionMetadata(
                20_000L, 1920, 1080, "h264", "aac", "mp4", new BigDecimal("29.970"), 800_000L, true, true), NOW);
        return asset;
    }
}
