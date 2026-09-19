package com.fdmultimedia.api.contentsuggestions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.contentdrafts.ContentDraftRepository;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobCreateRequest;
import com.fdmultimedia.api.jobs.JobService;
import com.fdmultimedia.api.jobs.JobStatus;
import com.fdmultimedia.api.jobs.JobSummary;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.transcripts.MediaTranscriptRepository;
import com.fdmultimedia.api.transcripts.TranscriptSegmentRepository;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.WorkerCredential;
import com.fdmultimedia.api.workers.WorkerRegistrationRequest;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ContentSuggestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final ContentSuggestionRepository suggestions = mock(ContentSuggestionRepository.class);
    private final ContentDraftRepository drafts = mock(ContentDraftRepository.class);
    private final MediaTranscriptRepository transcripts = mock(MediaTranscriptRepository.class);
    private final TranscriptSegmentRepository transcriptSegments = mock(TranscriptSegmentRepository.class);
    private final ContentAiProperties properties = new ContentAiProperties();
    private final ContentEnrichmentContextBuilder contextBuilder = new ContentEnrichmentContextBuilder(transcripts, transcriptSegments, properties);
    private final SocialCopyPromptBuilder promptBuilder = new SocialCopyPromptBuilder();
    private final JobService jobService = mock(JobService.class);
    private final ContentSuggestionService service = new ContentSuggestionService(
            authService, suggestions, drafts, contextBuilder, promptBuilder, properties, jobService, Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private ContentDraft draft;
    private Job generationJob;
    private Worker worker;
    private WorkerPrincipal workerPrincipal;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        draft = draftReadyFromExistingAsset();
        generationJob = new Job(workspace, JobType.GENERATE_SOCIAL_COPY, Map.of("draftId", draft.getId().toString()), 3, NOW);
        WorkerCredential credential = new WorkerCredential(UUID.randomUUID(), workspace, "local-agent", "$2a$10$hash");
        worker = new Worker(workspace, credential, registration(), NOW);
        workerPrincipal = new WorkerPrincipal(credential);
        when(transcripts.findByWorkspaceAndAssetOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(suggestions.save(any(ContentSuggestion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            Worker assignedWorker = invocation.getArgument(1);
            Map<String, Object> result = invocation.getArgument(2);
            Instant now = invocation.getArgument(3);
            job.complete(assignedWorker, result, now);
            return null;
        }).when(jobService).completeOwnedJob(any(Job.class), any(Worker.class), any(Map.class), any(Instant.class));
        doAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            Worker assignedWorker = invocation.getArgument(1);
            String errorCode = invocation.getArgument(2);
            String errorMessage = invocation.getArgument(3);
            boolean terminal = invocation.getArgument(4);
            Instant now = invocation.getArgument(5);
            if (terminal) {
                job.failTerminal(assignedWorker, errorCode, errorMessage, now);
            } else {
                job.fail(assignedWorker, errorCode, errorMessage, now);
            }
            return null;
        }).when(jobService).failOwnedJob(any(Job.class), any(Worker.class), any(), any(), anyBoolean(), any(Instant.class));
    }

    // ---- create ----

    @Test
    void createsSuggestionAndJobExactlyOnce() {
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));
        when(jobService.createForWorkspace(any(), any())).thenReturn(jobSummary(generationJob));
        when(jobService.getJobEntityForWorkspace(workspace, generationJob.getId())).thenReturn(Optional.of(generationJob));

        ContentSuggestionSummary summary = service.create(user, draft.getId(), new CreateContentSuggestionRequest(SuggestionLanguage.ENGLISH, SuggestionTone.CASUAL));

        ArgumentCaptor<JobCreateRequest> request = ArgumentCaptor.forClass(JobCreateRequest.class);
        verify(jobService, times(1)).createForWorkspace(any(), request.capture());
        assertThat(request.getValue().type()).isEqualTo(JobType.GENERATE_SOCIAL_COPY);
        assertThat(summary.status()).isEqualTo(ContentSuggestionStatus.PENDING);
        assertThat(summary.language()).isEqualTo(SuggestionLanguage.ENGLISH);
        assertThat(summary.tone()).isEqualTo(SuggestionTone.CASUAL);
        assertThat(summary.provider()).isEqualTo("DETERMINISTIC_TEST");
        assertThat(summary.promptVersion()).isEqualTo(SocialCopyPromptBuilder.VERSION);
        assertThat(summary.transcriptUsed()).isFalse();
    }

    @Test
    void rejectsGenerationWhenAiDisabled() {
        properties.setEnabled(false);
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.create(user, draft.getId(), new CreateContentSuggestionRequest(SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("reason")
                .isEqualTo("AI_DISABLED");
        verify(jobService, never()).createForWorkspace(any(), any());
    }

    @Test
    void rejectsGenerationWhenDraftNotReady() {
        ContentDraft notReady = draftInClipPending();
        when(drafts.findByWorkspaceAndId(workspace, notReady.getId())).thenReturn(Optional.of(notReady));

        assertThatThrownBy(() -> service.create(user, notReady.getId(), new CreateContentSuggestionRequest(SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsGenerationForDraftFromAnotherWorkspace() {
        UUID otherId = UUID.randomUUID();
        when(drafts.findByWorkspaceAndId(workspace, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(user, otherId, new CreateContentSuggestionRequest(SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- list/get ----

    @Test
    void listsSuggestionHistoryNewestFirstWithoutOverwriting() {
        ContentSuggestion first = readySuggestion();
        ContentSuggestion second = readySuggestion();
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));
        when(suggestions.findByContentDraftOrderByCreatedAtDesc(draft)).thenReturn(List.of(second, first));

        List<ContentSuggestionSummary> history = service.listFor(user, draft.getId());

        assertThat(history).hasSize(2);
        assertThat(history.get(0).id()).isEqualTo(second.getId());
        assertThat(history.get(1).id()).isEqualTo(first.getId());
    }

    @Test
    void getForRejectsSuggestionFromAnotherWorkspace() {
        UUID otherId = UUID.randomUUID();
        when(suggestions.findByWorkspaceAndId(workspace, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFor(user, otherId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- discard ----

    @Test
    void discardsReadySuggestion() {
        ContentSuggestion suggestion = readySuggestion();
        when(suggestions.findByWorkspaceAndIdForUpdate(workspace, suggestion.getId())).thenReturn(Optional.of(suggestion));

        ContentSuggestionSummary summary = service.discard(user, suggestion.getId());

        assertThat(summary.status()).isEqualTo(ContentSuggestionStatus.DISCARDED);
    }

    @Test
    void rejectsDiscardingAlreadyAppliedSuggestion() {
        ContentSuggestion suggestion = readySuggestion();
        suggestion.markApplied(owner.getId(), NOW);
        when(suggestions.findByWorkspaceAndIdForUpdate(workspace, suggestion.getId())).thenReturn(Optional.of(suggestion));

        assertThatThrownBy(() -> service.discard(user, suggestion.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- worker authorize/complete/fail ----

    @Test
    void authorizesGenerationAndMarksGenerating() {
        ContentSuggestion suggestion = pendingSuggestion();
        claimJob();
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, generationJob.getId())).thenReturn(generationJob);
        when(suggestions.findByGenerationJob(generationJob)).thenReturn(Optional.of(suggestion));

        WorkerContentSuggestionAuthorizationResponse response = service.authorizeWorkerGeneration(workerPrincipal, generationJob.getId(), "machine-1");

        assertThat(response.suggestionId()).isEqualTo(suggestion.getId());
        assertThat(response.provider()).isEqualTo("DETERMINISTIC_TEST");
        assertThat(response.maxCaptionLength()).isEqualTo(properties.getMaxCaptionLength());
        assertThat(suggestion.getStatus()).isEqualTo(ContentSuggestionStatus.GENERATING);
    }

    @Test
    void completesGenerationWithValidOutput() {
        ContentSuggestion suggestion = generatingSuggestion();
        claimAndStartJob();
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, generationJob.getId())).thenReturn(generationJob);
        when(suggestions.findByGenerationJob(generationJob)).thenReturn(Optional.of(suggestion));

        ContentSuggestionSummary summary = service.completeWorkerGeneration(workerPrincipal, generationJob.getId(), new WorkerContentSuggestionCompletionRequest(
                "machine-1", suggestion.getId(), "Big news!", "This is the caption.", List.of("#AI", "ai", " tech "), "Short", 10, 20, 30, 500L));

        assertThat(summary.status()).isEqualTo(ContentSuggestionStatus.READY);
        assertThat(summary.hook()).isEqualTo("Big news!");
        assertThat(summary.hashtags()).containsExactly("ai", "tech");
        assertThat(summary.shortTitle()).isEqualTo("Short");
        assertThat(generationJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void rejectsEmptyHookAsOutputRejected() {
        assertOutputRejected(new WorkerContentSuggestionCompletionRequest(
                "machine-1", null, "", "caption", List.of(), null, null, null, null, null));
    }

    @Test
    void rejectsOversizedCaption() {
        String tooLong = "x".repeat(properties.getMaxCaptionLength() + 1);
        assertOutputRejected(new WorkerContentSuggestionCompletionRequest(
                "machine-1", null, "hook", tooLong, List.of(), null, null, null, null, null));
    }

    @Test
    void rejectsTooManyHashtags() {
        List<String> many = java.util.stream.IntStream.range(0, properties.getMaxHashtags() + 1)
                .mapToObj(i -> "tag" + i)
                .toList();
        assertOutputRejected(new WorkerContentSuggestionCompletionRequest(
                "machine-1", null, "hook", "caption", many, null, null, null, null, null));
    }

    @Test
    void rejectsHashtagContainingWhitespace() {
        assertOutputRejected(new WorkerContentSuggestionCompletionRequest(
                "machine-1", null, "hook", "caption", List.of("content marketing"), null, null, null, null, null));
    }

    @Test
    void rejectsOversizedShortTitle() {
        String tooLong = "x".repeat(properties.getMaxShortTitleLength() + 1);
        assertOutputRejected(new WorkerContentSuggestionCompletionRequest(
                "machine-1", null, "hook", "caption", List.of(), tooLong, null, null, null, null));
    }

    @Test
    void rejectsNegativeTokenCounts() {
        assertOutputRejected(new WorkerContentSuggestionCompletionRequest(
                "machine-1", null, "hook", "caption", List.of(), null, -1, null, null, null));
    }

    @Test
    void deduplicatesHashtagsCaseInsensitively() {
        ContentSuggestion suggestion = generatingSuggestion();
        claimAndStartJob();
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, generationJob.getId())).thenReturn(generationJob);
        when(suggestions.findByGenerationJob(generationJob)).thenReturn(Optional.of(suggestion));

        ContentSuggestionSummary summary = service.completeWorkerGeneration(workerPrincipal, generationJob.getId(), new WorkerContentSuggestionCompletionRequest(
                "machine-1", suggestion.getId(), "hook", "caption", List.of("AI", "ai", "#AI", "ML"), null, null, null, null, null));

        assertThat(summary.hashtags()).containsExactly("ai", "ml");
    }

    @Test
    void failGenerationRequeuesNonTerminalFailureAsPending() {
        ContentSuggestion suggestion = generatingSuggestion();
        claimAndStartJob();
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, generationJob.getId())).thenReturn(generationJob);
        when(suggestions.findByGenerationJob(generationJob)).thenReturn(Optional.of(suggestion));

        ContentSuggestionSummary summary = service.failWorkerGeneration(workerPrincipal, generationJob.getId(),
                new WorkerContentSuggestionFailureRequest("machine-1", suggestion.getId(), "AI_PROVIDER_UNAVAILABLE", "boom", false));

        assertThat(summary.status()).isEqualTo(ContentSuggestionStatus.PENDING);
        assertThat(generationJob.getStatus()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void failGenerationMarksTerminalFailure() {
        ContentSuggestion suggestion = generatingSuggestion();
        claimAndStartJob();
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, generationJob.getId())).thenReturn(generationJob);
        when(suggestions.findByGenerationJob(generationJob)).thenReturn(Optional.of(suggestion));

        ContentSuggestionSummary summary = service.failWorkerGeneration(workerPrincipal, generationJob.getId(),
                new WorkerContentSuggestionFailureRequest("machine-1", suggestion.getId(), "AI_AUTHENTICATION_FAILED", "bad key", true));

        assertThat(summary.status()).isEqualTo(ContentSuggestionStatus.FAILED);
        assertThat(summary.failureCode()).isEqualTo("AI_AUTHENTICATION_FAILED");
    }

    // ---- apply ----

    @Test
    void appliesReadySuggestionAndComposesCaption() {
        ContentSuggestion suggestion = readySuggestionWithOutput("Hook!", "Body text.", List.of("ai", "tech"));
        when(suggestions.findByWorkspaceAndIdForUpdate(workspace, suggestion.getId())).thenReturn(Optional.of(suggestion));
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));

        ContentSuggestionSummary summary = service.apply(user, suggestion.getId());

        assertThat(summary.status()).isEqualTo(ContentSuggestionStatus.APPLIED);
        assertThat(draft.getCaption()).isEqualTo("Hook!\n\nBody text.\n\n#ai #tech");
    }

    @Test
    void rejectsDoubleApply() {
        ContentSuggestion suggestion = readySuggestionWithOutput("Hook!", "Body.", List.of());
        when(suggestions.findByWorkspaceAndIdForUpdate(workspace, suggestion.getId())).thenReturn(Optional.of(suggestion));
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));
        service.apply(user, suggestion.getId());

        assertThatThrownBy(() -> service.apply(user, suggestion.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("reason")
                .isEqualTo("SUGGESTION_ALREADY_APPLIED");
    }

    @Test
    void rejectsApplyingDiscardedSuggestion() {
        ContentSuggestion suggestion = readySuggestion();
        suggestion.discard();
        when(suggestions.findByWorkspaceAndIdForUpdate(workspace, suggestion.getId())).thenReturn(Optional.of(suggestion));

        assertThatThrownBy(() -> service.apply(user, suggestion.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsApplyingFailedSuggestion() {
        ContentSuggestion suggestion = pendingSuggestion();
        suggestion.markFailed("AI_TIMEOUT", "timed out", NOW);
        when(suggestions.findByWorkspaceAndIdForUpdate(workspace, suggestion.getId())).thenReturn(Optional.of(suggestion));

        assertThatThrownBy(() -> service.apply(user, suggestion.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsStaleApplyAfterHumanEditsDraftCaption() {
        ContentSuggestion suggestion = readySuggestionWithOutput("Hook!", "Body.", List.of());
        when(suggestions.findByWorkspaceAndIdForUpdate(workspace, suggestion.getId())).thenReturn(Optional.of(suggestion));
        draft.updateEditableFields(draft.getTitle(), "A human changed this caption already.", NOW);
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.apply(user, suggestion.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("reason")
                .isEqualTo("SUGGESTION_STALE");
        assertThat(draft.getCaption()).isEqualTo("A human changed this caption already.");
    }

    @Test
    void regenerateAfterStaleAppliesSuccessfully() {
        draft.updateEditableFields(draft.getTitle(), "Edited caption.", NOW);
        ContentSuggestion regenerated = readySuggestionWithOutput("New hook", "New body.", List.of());
        when(suggestions.findByWorkspaceAndIdForUpdate(workspace, regenerated.getId())).thenReturn(Optional.of(regenerated));
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));

        ContentSuggestionSummary summary = service.apply(user, regenerated.getId());

        assertThat(summary.status()).isEqualTo(ContentSuggestionStatus.APPLIED);
    }

    @Test
    void applyRejectsSuggestionFromAnotherWorkspace() {
        UUID otherId = UUID.randomUUID();
        when(suggestions.findByWorkspaceAndIdForUpdate(workspace, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(user, otherId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private void assertOutputRejected(WorkerContentSuggestionCompletionRequest partialRequest) {
        ContentSuggestion suggestion = generatingSuggestion();
        claimAndStartJob();
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, generationJob.getId())).thenReturn(generationJob);
        when(suggestions.findByGenerationJob(generationJob)).thenReturn(Optional.of(suggestion));
        WorkerContentSuggestionCompletionRequest request = new WorkerContentSuggestionCompletionRequest(
                partialRequest.machineIdentifier(), suggestion.getId(), partialRequest.hook(), partialRequest.caption(),
                partialRequest.hashtags(), partialRequest.shortTitle(), partialRequest.promptTokens(),
                partialRequest.completionTokens(), partialRequest.totalTokens(), partialRequest.latencyMs());

        ContentSuggestionSummary summary = service.completeWorkerGeneration(workerPrincipal, generationJob.getId(), request);

        assertThat(summary.status()).isEqualTo(ContentSuggestionStatus.FAILED);
        assertThat(summary.failureCode()).isEqualTo("AI_OUTPUT_REJECTED");
        assertThat(generationJob.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    private void claimJob() {
        generationJob.claim(worker, NOW.minusSeconds(1), NOW.plusSeconds(30));
    }

    private void claimAndStartJob() {
        claimJob();
        generationJob.start(worker, NOW, NOW.plusSeconds(30));
    }

    private ContentSuggestion pendingSuggestion() {
        return new ContentSuggestion(
                workspace, draft, generationJob, "DETERMINISTIC_TEST", "deterministic-v1", SocialCopyPromptBuilder.VERSION,
                SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL, "prompt text", fingerprintFor(draft), false, null, owner, NOW);
    }

    private ContentSuggestion generatingSuggestion() {
        ContentSuggestion suggestion = pendingSuggestion();
        suggestion.markGenerating(NOW);
        return suggestion;
    }

    private ContentSuggestion readySuggestion() {
        return readySuggestionWithOutput("hook", "caption", List.of("tag"));
    }

    private ContentSuggestion readySuggestionWithOutput(String hook, String caption, List<String> hashtags) {
        Job job = new Job(workspace, JobType.GENERATE_SOCIAL_COPY, Map.of("draftId", draft.getId().toString()), 3, NOW);
        ContentSuggestion suggestion = new ContentSuggestion(
                workspace, draft, job, "DETERMINISTIC_TEST", "deterministic-v1", SocialCopyPromptBuilder.VERSION,
                SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL, "prompt text", fingerprintFor(draft), false, null, owner, NOW);
        suggestion.markGenerating(NOW);
        suggestion.markReady(hook, caption, hashtags, null, null, null, null, null, NOW);
        return suggestion;
    }

    /** Mirrors ContentSuggestionService's own private fingerprint algorithm so tests can construct an already-matching suggestion. */
    private String fingerprintFor(ContentDraft draft) {
        try {
            String material = String.join("|",
                    SocialCopyPromptBuilder.VERSION,
                    draft.getId().toString(),
                    draft.getTitle() == null ? "" : draft.getTitle(),
                    draft.getCaption() == null ? "" : draft.getCaption(),
                    draft.getSourceAsset().getId().toString(),
                    draft.getSourceHighlightCandidate() == null ? "" : draft.getSourceHighlightCandidate().getId().toString(),
                    SuggestionLanguage.AUTO.name(),
                    SuggestionTone.NEUTRAL.name(),
                    "DETERMINISTIC_TEST",
                    "deterministic-v1");
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private ContentDraft draftReadyFromExistingAsset() {
        MediaAsset asset = readyInspectedVideoAsset();
        return ContentDraft.fromExistingAsset(workspace, asset, "Title", null, owner, NOW);
    }

    private ContentDraft draftInClipPending() {
        MediaAsset source = readyInspectedVideoAsset();
        com.fdmultimedia.api.highlights.HighlightCandidate candidate = highlightCandidate(source, 1_000, 6_000);
        MediaAsset clipInProgress = new MediaAsset(workspace, owner, "https://example.com/clip.mp4", NOW);
        Job clipJob = new Job(workspace, JobType.CREATE_CLIP, Map.of(), 3, NOW);
        return ContentDraft.fromHighlightCandidate(workspace, candidate, clipInProgress, clipJob, owner, NOW);
    }

    private com.fdmultimedia.api.highlights.HighlightCandidate highlightCandidate(MediaAsset source, long startMs, long endMs) {
        Job analysisJob = new Job(workspace, JobType.ANALYZE_HIGHLIGHTS, Map.of(), 3, NOW);
        com.fdmultimedia.api.highlights.HighlightAnalysis analysis =
                new com.fdmultimedia.api.highlights.HighlightAnalysis(workspace, source, analysisJob, "DETERMINISTIC_V1", "v1", NOW);
        analysis.markRunning(NOW);
        analysis.markSucceeded(NOW);
        return new com.fdmultimedia.api.highlights.HighlightCandidate(analysis, startMs, endMs, new BigDecimal("0.9"), "reason", 1, NOW);
    }

    private MediaAsset readyInspectedVideoAsset() {
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

    private JobSummary jobSummary(Job job) {
        return new JobSummary(
                job.getId(), job.getType(), job.getStatus(), job.getPayload(), job.getResult(),
                job.getErrorCode(), job.getErrorMessage(), null, null, job.getAttemptCount(), job.getMaxAttempts(),
                job.getQueuedAt(), job.getAssignedAt(), job.getStartedAt(), job.getFinishedAt(),
                job.getLeaseExpiresAt(), job.getCreatedAt(), job.getUpdatedAt());
    }

    private WorkerRegistrationRequest registration() {
        return new WorkerRegistrationRequest(
                "machine-1", "Node A", "Windows 11", "amd64", "AMD Ryzen", 16, 34_359_738_368L, null, null, "fdm-worker/0.1.0");
    }
}
