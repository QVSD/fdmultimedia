package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HighlightSelectionService {
    private static final Logger log = LoggerFactory.getLogger(HighlightSelectionService.class);
    private static final int DEFAULT_COUNT = 3;
    private static final int MAX_COUNT = 5;
    private static final int MAX_HISTORY = 20;

    private final AuthService authService;
    private final HighlightAnalysisRepository analyses;
    private final HighlightCandidateRepository candidates;
    private final HighlightSelectionRepository selections;
    private final HighlightSelectionItemRepository items;
    private final HighlightSelectionExclusionRepository exclusions;
    private final HighlightSelectionClipCreator clipCreator;
    private final HighlightProperties properties;
    private final Clock clock;
    private final HighlightDiversitySelector selector = new HighlightDiversitySelector();

    public HighlightSelectionService(AuthService authService, HighlightAnalysisRepository analyses,
            HighlightCandidateRepository candidates, HighlightSelectionRepository selections,
            HighlightSelectionItemRepository items, HighlightSelectionExclusionRepository exclusions,
            HighlightSelectionClipCreator clipCreator,
            HighlightProperties properties, Clock clock) {
        this.authService = authService; this.analyses = analyses; this.candidates = candidates;
        this.selections = selections; this.items = items; this.exclusions = exclusions;
        this.clipCreator = clipCreator;
        this.properties = properties; this.clock = clock;
    }

    @Transactional
    public HighlightSelectionSummary create(AuthenticatedUser principal, UUID analysisId, CreateHighlightSelectionRequest request) {
        Workspace workspace = currentWorkspace(principal);
        int count = request == null || request.count() == null ? DEFAULT_COUNT : request.count();
        if (count < 1 || count > MAX_COUNT) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "count must be between 1 and 5");
        HighlightAnalysis analysis = analyses.findByWorkspaceAndId(workspace, analysisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Highlight analysis not found"));
        if (analysis.getStatus() != HighlightAnalysisStatus.SUCCEEDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Highlight analysis is not complete");
        }
        if (!properties.getV3AnalyzerType().equals(analysis.getAnalyzerType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A successful V3 analysis is required");
        }
        List<HighlightCandidate> pool = candidates.findByAnalysisOrderByRankAsc(analysis);
        Instant started = Instant.now(clock);
        HighlightDiversitySelector.SelectionResult result = selector.select(pool, count);
        HighlightSelection selection = selections.save(new HighlightSelection(
                analysis, HighlightDiversitySelector.VERSION, count, result.selected().size(), started));
        List<HighlightSelectionItem> selected = new ArrayList<>();
        for (int i = 0; i < result.selected().size(); i++) {
            selected.add(new HighlightSelectionItem(selection, result.selected().get(i), i + 1));
        }
        items.saveAll(selected);
        List<HighlightSelectionExclusion> skipped = result.excluded().stream()
                .map(e -> new HighlightSelectionExclusion(selection, e.candidate(), e.reason(), e.conflicting(),
                        e.temporalOverlap(), e.lexicalSimilarity()))
                .toList();
        exclusions.saveAll(skipped);
        long elapsedMs = Duration.between(started, Instant.now(clock)).toMillis();
        log.info("Highlight selection {} analysis={} selector={} pool={} requested={} selected={} excluded={} durationMs={}",
                selection.getId(), analysisId, HighlightDiversitySelector.VERSION, pool.size(), count,
                selected.size(), skipped.size(), elapsedMs);
        return toSummary(selection, selected, skipped);
    }

    @Transactional(readOnly = true)
    public HighlightSelectionSummary get(AuthenticatedUser principal, UUID selectionId) {
        HighlightSelection selection = requireSelection(currentWorkspace(principal), selectionId);
        return toSummary(selection, items.findBySelectionOrderBySelectionOrderAsc(selection),
                exclusions.findBySelectionOrderByCandidateRankAsc(selection));
    }

    @Transactional(readOnly = true)
    public List<HighlightSelectionSummary> list(AuthenticatedUser principal, UUID analysisId) {
        Workspace workspace = currentWorkspace(principal);
        HighlightAnalysis analysis = analyses.findByWorkspaceAndId(workspace, analysisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Highlight analysis not found"));
        return selections.findByWorkspaceAndAnalysisOrderByCreatedAtDesc(workspace, analysis, PageRequest.of(0, MAX_HISTORY)).stream()
                .map(s -> toSummary(s, items.findBySelectionOrderBySelectionOrderAsc(s),
                        exclusions.findBySelectionOrderByCandidateRankAsc(s))).toList();
    }

    public HighlightSelectionSummary createClips(AuthenticatedUser principal, UUID selectionId) {
        Workspace workspace = currentWorkspace(principal);
        HighlightSelection selection = requireSelection(workspace, selectionId);
        List<UUID> itemIds = items.findBySelectionOrderBySelectionOrderAsc(selection).stream()
                .map(HighlightSelectionItem::getId).toList();
        for (UUID itemId : itemIds) {
            try {
                clipCreator.create(principal, workspace, itemId);
            } catch (RuntimeException exception) {
                String code = exception instanceof ResponseStatusException response
                        ? "CLIP_REQUEST_" + response.getStatusCode().value() : "CLIP_REQUEST_FAILED";
                String message = exception instanceof ResponseStatusException response && response.getReason() != null
                        ? response.getReason() : "Clip request failed";
                clipCreator.recordFailure(workspace, itemId, code, message);
            }
        }
        return get(principal, selectionId);
    }

    private HighlightSelection requireSelection(Workspace workspace, UUID id) {
        return selections.findByWorkspaceAndId(workspace, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Highlight selection not found"));
    }

    private HighlightSelectionSummary toSummary(HighlightSelection selection, List<HighlightSelectionItem> selected,
            List<HighlightSelectionExclusion> skipped) {
        return new HighlightSelectionSummary(selection.getId(), selection.getMediaAsset().getId(),
                selection.getAnalysis().getId(), selection.getSelectorVersion(), selection.getRequestedCount(),
                selection.getSelectedCount(), selection.getStatus(), selection.getCreatedAt(),
                selected.stream().map(i -> new HighlightSelectionSummary.Item(i.getId(), i.getCandidate().getId(),
                        i.getSelectionOrder(), i.getSourceRank(), i.getCandidate().getStartMs(), i.getCandidate().getEndMs(),
                        i.getCandidate().getScore(), i.getCandidate().getTranscriptExcerpt(),
                        i.getClipAsset() == null ? null : i.getClipAsset().getId(),
                        i.getClipAsset() == null ? null : i.getClipAsset().getStatus(),
                        i.getClipJob() == null ? null : i.getClipJob().getId(),
                        i.getClipJob() == null ? null : i.getClipJob().getStatus(),
                        i.getClipRequestFailureCode(), i.getClipRequestFailureMessage())).toList(),
                skipped.stream().map(e -> new HighlightSelectionSummary.Exclusion(e.getCandidate().getId(),
                        e.getCandidate().getRank(), e.getReason(),
                        e.getConflictingCandidate() == null ? null : e.getConflictingCandidate().getId(),
                        e.getConflictingCandidate() == null ? null : e.getConflictingCandidate().getRank(),
                        e.getTemporalOverlapRatio(), e.getLexicalSimilarity())).toList());
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }
}
