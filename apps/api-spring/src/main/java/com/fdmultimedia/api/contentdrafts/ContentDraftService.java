package com.fdmultimedia.api.contentdrafts;

import com.fdmultimedia.api.assets.CreateClipRequest;
import com.fdmultimedia.api.assets.CreateClipResponse;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaAssetService;
import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.assets.MediaDerivationType;
import com.fdmultimedia.api.assets.MediaInspectionStatus;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.highlights.HighlightAnalysisStatus;
import com.fdmultimedia.api.highlights.HighlightCandidate;
import com.fdmultimedia.api.highlights.HighlightCandidateRepository;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobService;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.publishing.PublicationRepository;
import com.fdmultimedia.api.publishing.PublicationStatus;
import com.fdmultimedia.api.publishing.PublicationSummary;
import com.fdmultimedia.api.publishing.PublishingService;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContentDraftService {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_CAPTION_LENGTH = 2200;

    private final AuthService authService;
    private final ContentDraftRepository drafts;
    private final MediaAssetRepository assets;
    private final MediaAssetService mediaAssetService;
    private final HighlightCandidateRepository candidates;
    private final JobService jobService;
    private final PublicationRepository publications;
    private final PublishingService publishingService;
    private final Clock clock;

    public ContentDraftService(
            AuthService authService,
            ContentDraftRepository drafts,
            MediaAssetRepository assets,
            MediaAssetService mediaAssetService,
            HighlightCandidateRepository candidates,
            JobService jobService,
            PublicationRepository publications,
            PublishingService publishingService,
            Clock clock) {
        this.authService = authService;
        this.drafts = drafts;
        this.assets = assets;
        this.mediaAssetService = mediaAssetService;
        this.candidates = candidates;
        this.jobService = jobService;
        this.publications = publications;
        this.publishingService = publishingService;
        this.clock = clock;
    }

    @Transactional
    public ContentDraftSummary createFromAsset(AuthenticatedUser principal, CreateContentDraftRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        MediaAsset asset = assets.findByWorkspaceAndId(workspace, request.assetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        validateReadyForDraft(asset);
        Instant now = Instant.now(clock);
        ContentDraft draft = ContentDraft.fromExistingAsset(
                workspace, asset, validateTitle(request.title()), validateCaption(request.caption()), membership.getUser(), now);
        return toSummary(drafts.save(draft), workspace);
    }

    @Transactional
    public ContentDraftSummary createFromHighlightCandidate(AuthenticatedUser principal, UUID candidateId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        HighlightCandidate candidate = candidates.findByWorkspaceAndId(workspace, candidateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Highlight candidate not found"));
        if (candidate.getAnalysis().getStatus() != HighlightAnalysisStatus.SUCCEEDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Highlight analysis is not complete");
        }
        long durationMs = candidate.getEndMs() - candidate.getStartMs();
        CreateClipResponse clipResponse = mediaAssetService.createClip(
                principal, candidate.getAsset().getId(), new CreateClipRequest(candidate.getStartMs(), durationMs));
        MediaAsset clipAsset = assets.findByWorkspaceAndId(workspace, clipResponse.asset().id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Clip asset was not created"));
        Job clipJob = jobService.getJobEntityForWorkspace(workspace, clipResponse.job().id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Clip job was not created"));
        Instant now = Instant.now(clock);
        ContentDraft draft = ContentDraft.fromHighlightCandidate(workspace, candidate, clipAsset, clipJob, membership.getUser(), now);
        return toSummary(drafts.save(draft), workspace);
    }

    @Transactional
    public List<ContentDraftSummary> listFor(AuthenticatedUser principal) {
        Workspace workspace = currentWorkspace(principal);
        return drafts.findByWorkspaceOrderByCreatedAtDesc(workspace).stream()
                .map(draft -> toSummary(reconcile(principal, workspace, draft.getId()), workspace))
                .toList();
    }

    @Transactional
    public ContentDraftSummary getFor(AuthenticatedUser principal, UUID draftId) {
        Workspace workspace = currentWorkspace(principal);
        ContentDraft draft = reconcile(principal, workspace, draftId);
        return toSummary(draft, workspace);
    }

    @Transactional
    public ContentDraftSummary update(AuthenticatedUser principal, UUID draftId, UpdateContentDraftRequest request) {
        Workspace workspace = currentWorkspace(principal);
        ContentDraft draft = drafts.findByWorkspaceAndId(workspace, draftId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content draft not found"));
        draft.updateEditableFields(validateTitle(request.title()), validateCaption(request.caption()), Instant.now(clock));
        return toSummary(draft, workspace);
    }

    @Transactional
    public ContentDraftSummary publish(AuthenticatedUser principal, UUID draftId, PublishContentDraftRequest request) {
        Workspace workspace = currentWorkspace(principal);
        ContentDraft draft = reconcile(principal, workspace, draftId);
        if (draft.getStatus() != ContentDraftStatus.READY && draft.getStatus() != ContentDraftStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft is not ready to publish");
        }
        PublicationSummary publication = request.tiktokSettings() == null
                ? publishingService.createPublicationForDraft(
                        principal, draft.getMediaAsset(), request.socialAccountId(), draft.getCaption(), draft.getId())
                : publishingService.createPublicationForDraft(
                        principal, draft.getMediaAsset(), request.socialAccountId(), draft.getCaption(), draft.getId(), request.tiktokSettings());
        draft.applyPublishingDisplayState(ContentDraftStatus.PUBLISHING, null, Instant.now(clock));
        ContentDraftSummary summary = toSummary(draft, workspace);
        return summary.publications().stream().anyMatch(p -> p.id().equals(publication.id()))
                ? summary
                : withPublication(summary, publication);
    }

    @Transactional
    public ContentDraftSummary retryPreparation(AuthenticatedUser principal, UUID draftId) {
        Workspace workspace = currentWorkspace(principal);
        ContentDraft draft = drafts.findByWorkspaceAndIdForUpdate(workspace, draftId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content draft not found"));
        if (draft.getStatus() != ContentDraftStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft is not in a failed state");
        }
        MediaAsset failedAsset = draft.getMediaAsset();
        Instant now = Instant.now(clock);
        if (failedAsset.getDerivationType() == MediaDerivationType.CLIP) {
            HighlightCandidate candidate = draft.getSourceHighlightCandidate();
            if (candidate == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft has no highlight candidate to retry from");
            }
            long durationMs = candidate.getEndMs() - candidate.getStartMs();
            CreateClipResponse clipResponse = mediaAssetService.createClip(
                    principal, draft.getSourceAsset().getId(), new CreateClipRequest(candidate.getStartMs(), durationMs));
            MediaAsset clipAsset = assets.findByWorkspaceAndId(workspace, clipResponse.asset().id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Clip asset was not created"));
            Job clipJob = jobService.getJobEntityForWorkspace(workspace, clipResponse.job().id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Clip job was not created"));
            draft.retryPreparationAsClip(clipAsset, clipJob, now);
        } else if (failedAsset.getDerivationType() == MediaDerivationType.SOCIAL_VERTICAL) {
            MediaAsset clip = failedAsset.getParentAsset();
            if (clip == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Failed vertical has no source clip to retry from");
            }
            CreateClipResponse verticalResponse = mediaAssetService.createSocialVertical(principal, clip.getId());
            MediaAsset verticalAsset = assets.findByWorkspaceAndId(workspace, verticalResponse.asset().id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Vertical asset was not created"));
            Job verticalJob = jobService.getJobEntityForWorkspace(workspace, verticalResponse.job().id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Vertical job was not created"));
            draft.retryPreparationAsVertical(verticalAsset, verticalJob, now);
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft cannot be retried");
        }
        return toSummary(draft, workspace);
    }

    /**
     * Idempotent, row-locked reconciliation: advances the durable workflow
     * stage only when the derivative it is waiting on has itself reached a
     * terminal state, and re-derives the publishing display state from linked
     * Publications. Safe to call repeatedly (polling, page refresh, restart)
     * — the workflow stage and Publication rows are the only source of truth,
     * never an in-memory callback.
     */
    private ContentDraft reconcile(AuthenticatedUser principal, Workspace workspace, UUID draftId) {
        ContentDraft draft = drafts.findByWorkspaceAndIdForUpdate(workspace, draftId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content draft not found"));
        reconcileWorkflow(principal, draft);
        reconcilePublishingState(draft, workspace);
        return draft;
    }

    private void reconcileWorkflow(AuthenticatedUser principal, ContentDraft draft) {
        if (draft.getStatus() == ContentDraftStatus.FAILED || draft.getWorkflowStage() == ContentDraftWorkflowStage.READY) {
            return;
        }
        MediaAsset pending = draft.getMediaAsset();
        Instant now = Instant.now(clock);
        if (pending.getStatus() == MediaAssetStatus.FAILED) {
            draft.markPreparationFailed(pending.getErrorCode(), pending.getErrorMessage(), now);
            return;
        }
        boolean pendingReady = pending.getStatus() == MediaAssetStatus.READY
                && pending.getInspectionStatus() == MediaInspectionStatus.INSPECTED;
        if (!pendingReady) {
            return;
        }
        if (draft.getWorkflowStage() == ContentDraftWorkflowStage.CLIP_PENDING) {
            // A read request must never itself fail just because a derivative
            // precondition (e.g. dimensions) was not met — surface it as the
            // draft's own FAILED state instead, per the bounded-error UX rule.
            try {
                CreateClipResponse verticalResponse = mediaAssetService.createSocialVertical(principal, pending.getId());
                MediaAsset verticalAsset = assets.findByWorkspaceAndId(draft.getWorkspace(), verticalResponse.asset().id())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Vertical asset was not created"));
                Job verticalJob = jobService.getJobEntityForWorkspace(draft.getWorkspace(), verticalResponse.job().id())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Vertical job was not created"));
                draft.advanceToVerticalPending(verticalAsset, verticalJob, now);
            } catch (ResponseStatusException ex) {
                draft.markPreparationFailed("VERTICAL_CREATION_FAILED", ex.getReason(), now);
            }
        } else if (draft.getWorkflowStage() == ContentDraftWorkflowStage.VERTICAL_PENDING) {
            draft.markPreparationReady(now);
        }
    }

    private void reconcilePublishingState(ContentDraft draft, Workspace workspace) {
        if (draft.getStatus() == ContentDraftStatus.DRAFT || draft.getStatus() == ContentDraftStatus.FAILED) {
            return;
        }
        List<Publication> rows = publications.findByWorkspaceAndContentDraftIdOrderByCreatedAtDesc(workspace, draft.getId());
        if (rows.isEmpty()) {
            return;
        }
        boolean anyActive = rows.stream()
                .anyMatch(p -> p.getStatus() == PublicationStatus.PENDING || p.getStatus() == PublicationStatus.PUBLISHING);
        boolean anyPublished = rows.stream().anyMatch(p -> p.getStatus() == PublicationStatus.PUBLISHED);
        Instant now = Instant.now(clock);
        if (anyActive) {
            draft.applyPublishingDisplayState(ContentDraftStatus.PUBLISHING, null, now);
        } else if (anyPublished) {
            Instant publishedAt = rows.stream()
                    .filter(p -> p.getStatus() == PublicationStatus.PUBLISHED)
                    .map(Publication::getPublishedAt)
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(now);
            draft.applyPublishingDisplayState(ContentDraftStatus.PUBLISHED, publishedAt, now);
        } else {
            draft.applyPublishingDisplayState(ContentDraftStatus.READY, null, now);
        }
    }

    private void validateReadyForDraft(MediaAsset asset) {
        if (asset.getStatus() != MediaAssetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not ready");
        }
        if (asset.getInspectionStatus() != MediaInspectionStatus.INSPECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not inspected");
        }
        if (!Boolean.TRUE.equals(asset.getHasVideo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset must contain video");
        }
    }

    private String validateTitle(String title) {
        if (title == null) {
            return null;
        }
        String trimmed = title.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        return trimmed;
    }

    private String validateCaption(String caption) {
        if (caption == null) {
            return null;
        }
        String trimmed = caption.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_CAPTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "caption must be at most " + MAX_CAPTION_LENGTH + " characters");
        }
        return trimmed;
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private ContentDraftSummary withPublication(ContentDraftSummary summary, PublicationSummary publication) {
        List<PublicationSummary> merged = new java.util.ArrayList<>(summary.publications());
        merged.add(0, publication);
        return new ContentDraftSummary(
                summary.id(), summary.sourceAssetId(), summary.mediaAssetId(), summary.mediaAssetFilename(),
                summary.sourceHighlightCandidateId(), summary.title(), summary.caption(), summary.status(),
                summary.workflowStage(), summary.pendingJobId(), summary.failureCode(), summary.failureMessage(),
                summary.createdAt(), summary.updatedAt(), summary.publishedAt(), merged, summary.robotRunId());
    }

    private ContentDraftSummary toSummary(ContentDraft draft, Workspace workspace) {
        List<PublicationSummary> publicationSummaries = publishingService.listForContentDraft(workspace, draft.getId());
        return new ContentDraftSummary(
                draft.getId(),
                draft.getSourceAsset().getId(),
                draft.getMediaAsset().getId(),
                draft.getMediaAsset().getOriginalFilename(),
                draft.getSourceHighlightCandidate() == null ? null : draft.getSourceHighlightCandidate().getId(),
                draft.getTitle(),
                draft.getCaption(),
                draft.getStatus(),
                draft.getWorkflowStage(),
                draft.getPendingJob() == null ? null : draft.getPendingJob().getId(),
                draft.getFailureCode(),
                draft.getFailureMessage(),
                draft.getCreatedAt(),
                draft.getUpdatedAt(),
                draft.getPublishedAt(),
                publicationSummaries,
                draft.getRobotRunId());
    }
}
