package com.fdmultimedia.api.highlights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fdmultimedia.api.assets.*;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.jobs.*;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class HighlightSelectionServiceTest {
    private final AuthService auth = mock(AuthService.class);
    private final HighlightAnalysisRepository analyses = mock(HighlightAnalysisRepository.class);
    private final HighlightCandidateRepository candidates = mock(HighlightCandidateRepository.class);
    private final HighlightSelectionRepository selections = mock(HighlightSelectionRepository.class);
    private final HighlightSelectionItemRepository items = mock(HighlightSelectionItemRepository.class);
    private final HighlightSelectionExclusionRepository exclusions = mock(HighlightSelectionExclusionRepository.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final JobService jobs = mock(JobService.class);
    private final MediaAssetService mediaAssets = mock(MediaAssetService.class);
    private final HighlightSelectionClipCreator clipCreator = new HighlightSelectionClipCreator(items, assets, jobs, mediaAssets);
    private final HighlightProperties properties = new HighlightProperties();
    private final HighlightSelectionService service = new HighlightSelectionService(auth, analyses, candidates,
            selections, items, exclusions, clipCreator, properties,
            Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC));
    private Workspace workspace;
    private AuthenticatedUser principal;
    private HighlightAnalysis analysis;
    private MediaAsset asset;

    @BeforeEach void setUp() {
        workspace = new Workspace("Test", "test");
        AppUser user = new AppUser("owner@example.com", "hash", "Owner");
        principal = new AuthenticatedUser(user);
        when(auth.currentMembershipFor(principal)).thenReturn(new WorkspaceMembership(workspace, user, WorkspaceRole.OWNER));
        analysis = mock(HighlightAnalysis.class); asset = mock(MediaAsset.class);
        when(analysis.getId()).thenReturn(UUID.randomUUID()); when(analysis.getWorkspace()).thenReturn(workspace);
        when(analysis.getAsset()).thenReturn(asset); when(asset.getId()).thenReturn(UUID.randomUUID());
        when(analysis.getStatus()).thenReturn(HighlightAnalysisStatus.SUCCEEDED);
        when(analysis.getAnalyzerType()).thenReturn("DETERMINISTIC_V3");
        when(selections.save(any())).thenAnswer(i -> i.getArgument(0));
        when(items.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(exclusions.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test void validatesCountBounds() {
        when(analyses.findByWorkspaceAndId(workspace, analysis.getId())).thenReturn(Optional.of(analysis));
        assertThatThrownBy(() -> service.create(principal, analysis.getId(), new CreateHighlightSelectionRequest(0)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.create(principal, analysis.getId(), new CreateHighlightSelectionRequest(6)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void requiresSuccessfulV3AndHidesForeignAnalysis() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(principal, id, new CreateHighlightSelectionRequest(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        when(analyses.findByWorkspaceAndId(workspace, id)).thenReturn(Optional.of(analysis));
        when(analysis.getAnalyzerType()).thenReturn("DETERMINISTIC_V2");
        assertThatThrownBy(() -> service.create(principal, id, new CreateHighlightSelectionRequest(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test void createsImmutableSelectionItemsAndExclusions() {
        HighlightCandidate first = candidate(1, 0, 10000, "same spoken idea");
        HighlightCandidate duplicate = candidate(2, 5000, 15000, "other words");
        HighlightCandidate distinct = candidate(3, 18000, 24000, "new separate topic");
        when(analyses.findByWorkspaceAndId(workspace, analysis.getId())).thenReturn(Optional.of(analysis));
        when(candidates.findByAnalysisOrderByRankAsc(analysis)).thenReturn(List.of(first, duplicate, distinct));

        HighlightSelectionSummary result = service.create(principal, analysis.getId(), new CreateHighlightSelectionRequest(2));

        assertThat(result.status()).isEqualTo(HighlightSelectionStatus.COMPLETE);
        assertThat(result.items()).extracting(HighlightSelectionSummary.Item::sourceRank).containsExactly(1, 3);
        assertThat(result.exclusions()).extracting(HighlightSelectionSummary.Exclusion::reason)
                .contains(HighlightSelectionExclusionReason.TEMPORAL_OVERLAP);
        verify(items).saveAll(any()); verify(exclusions).saveAll(any());
        verifyNoInteractions(mediaAssets);
    }

    @Test void bulkClipCreationAttachesOnceAndRepeatReusesExistingClip() {
        HighlightSelection selection = mock(HighlightSelection.class);
        when(selection.getId()).thenReturn(UUID.randomUUID()); when(selection.getWorkspace()).thenReturn(workspace);
        when(selection.getMediaAsset()).thenReturn(asset); when(selection.getAnalysis()).thenReturn(analysis);
        when(selection.getSelectorVersion()).thenReturn("DIVERSITY_SELECTOR_V1"); when(selection.getRequestedCount()).thenReturn(1);
        when(selection.getSelectedCount()).thenReturn(1); when(selection.getStatus()).thenReturn(HighlightSelectionStatus.COMPLETE);
        when(selection.getCreatedAt()).thenReturn(Instant.EPOCH);
        HighlightCandidate candidate = candidate(1, 1000, 6000, "clip me");
        HighlightSelectionItem item = spy(new HighlightSelectionItem(selection, candidate, 1));
        when(selections.findByWorkspaceAndId(workspace, selection.getId())).thenReturn(Optional.of(selection));
        when(items.findBySelectionOrderBySelectionOrderAsc(selection)).thenReturn(List.of(item));
        when(items.findByIdAndWorkspaceForUpdate(item.getId(), workspace)).thenReturn(Optional.of(item));
        when(exclusions.findBySelectionOrderByCandidateRankAsc(selection)).thenReturn(List.of());
        MediaAssetSummary assetSummary = mock(MediaAssetSummary.class); UUID clipId = UUID.randomUUID(); when(assetSummary.id()).thenReturn(clipId);
        JobSummary jobSummary = mock(JobSummary.class); UUID jobId = UUID.randomUUID(); when(jobSummary.id()).thenReturn(jobId);
        UUID sourceId = asset.getId();
        when(mediaAssets.createClip(eq(principal), eq(sourceId), any())).thenReturn(new CreateClipResponse(assetSummary, jobSummary));
        MediaAsset clip = mock(MediaAsset.class); when(clip.getId()).thenReturn(clipId); when(clip.getStatus()).thenReturn(MediaAssetStatus.PENDING);
        Job job = mock(Job.class); when(job.getId()).thenReturn(jobId); when(job.getStatus()).thenReturn(JobStatus.QUEUED);
        when(assets.findByWorkspaceAndId(workspace, clipId)).thenReturn(Optional.of(clip));
        when(jobs.getJobEntityForWorkspace(workspace, jobId)).thenReturn(Optional.of(job));

        service.createClips(principal, selection.getId());
        service.createClips(principal, selection.getId());

        verify(mediaAssets, times(1)).createClip(eq(principal), eq(sourceId), any());
        assertThat(item.getClipAsset()).isSameAs(clip);
    }

    @Test void bulkClipFailureDoesNotUndoSuccessfulIndependentItem() {
        HighlightSelection selection = mock(HighlightSelection.class);
        when(selection.getId()).thenReturn(UUID.randomUUID()); when(selection.getWorkspace()).thenReturn(workspace);
        when(selection.getMediaAsset()).thenReturn(asset); when(selection.getAnalysis()).thenReturn(analysis);
        when(selection.getSelectorVersion()).thenReturn("DIVERSITY_SELECTOR_V1"); when(selection.getRequestedCount()).thenReturn(2);
        when(selection.getSelectedCount()).thenReturn(2); when(selection.getStatus()).thenReturn(HighlightSelectionStatus.COMPLETE);
        when(selection.getCreatedAt()).thenReturn(Instant.EPOCH);
        HighlightSelectionItem first = new HighlightSelectionItem(selection, candidate(1, 1000, 6000, "first"), 1);
        HighlightSelectionItem second = new HighlightSelectionItem(selection, candidate(2, 9000, 14000, "second"), 2);
        when(selections.findByWorkspaceAndId(workspace, selection.getId())).thenReturn(Optional.of(selection));
        when(items.findBySelectionOrderBySelectionOrderAsc(selection)).thenReturn(List.of(first, second));
        when(items.findByIdAndWorkspaceForUpdate(first.getId(), workspace)).thenReturn(Optional.of(first));
        when(items.findByIdAndWorkspaceForUpdate(second.getId(), workspace)).thenReturn(Optional.of(second));
        when(exclusions.findBySelectionOrderByCandidateRankAsc(selection)).thenReturn(List.of());
        MediaAssetSummary assetSummary = mock(MediaAssetSummary.class); UUID clipId = UUID.randomUUID(); when(assetSummary.id()).thenReturn(clipId);
        JobSummary jobSummary = mock(JobSummary.class); UUID jobId = UUID.randomUUID(); when(jobSummary.id()).thenReturn(jobId);
        UUID sourceId = asset.getId();
        when(mediaAssets.createClip(eq(principal), eq(sourceId), any()))
                .thenReturn(new CreateClipResponse(assetSummary, jobSummary))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Source is no longer eligible"));
        MediaAsset clip = mock(MediaAsset.class); when(clip.getId()).thenReturn(clipId); when(clip.getStatus()).thenReturn(MediaAssetStatus.PENDING);
        Job job = mock(Job.class); when(job.getId()).thenReturn(jobId); when(job.getStatus()).thenReturn(JobStatus.QUEUED);
        when(assets.findByWorkspaceAndId(workspace, clipId)).thenReturn(Optional.of(clip));
        when(jobs.getJobEntityForWorkspace(workspace, jobId)).thenReturn(Optional.of(job));

        HighlightSelectionSummary result = service.createClips(principal, selection.getId());

        assertThat(first.getClipAsset()).isSameAs(clip);
        assertThat(second.getClipAsset()).isNull();
        assertThat(second.getClipRequestFailureCode()).isEqualTo("CLIP_REQUEST_409");
        assertThat(result.items().get(1).clipFailureMessage()).isEqualTo("Source is no longer eligible");
    }

    private HighlightCandidate candidate(int rank, long start, long end, String excerpt) {
        HighlightCandidate c = mock(HighlightCandidate.class);
        when(c.getId()).thenReturn(UUID.randomUUID()); when(c.getAnalysis()).thenReturn(analysis); when(c.getAsset()).thenReturn(asset);
        when(c.getRank()).thenReturn(rank); when(c.getStartMs()).thenReturn(start); when(c.getEndMs()).thenReturn(end);
        when(c.getScore()).thenReturn(new BigDecimal("0.8000")); when(c.getTranscriptExcerpt()).thenReturn(excerpt);
        return c;
    }
}
