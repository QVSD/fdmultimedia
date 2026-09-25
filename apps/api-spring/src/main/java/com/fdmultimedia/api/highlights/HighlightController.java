package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.assets.CreateClipResponse;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HighlightController {

    private final HighlightService highlightService;
    private final HighlightSelectionService selectionService;

    public HighlightController(HighlightService highlightService, HighlightSelectionService selectionService) {
        this.highlightService = highlightService;
        this.selectionService = selectionService;
    }

    @PostMapping("/assets/{assetId}/highlight-analyses")
    public HighlightAnalysisSummary createAnalysis(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID assetId,
            @RequestBody(required = false) CreateHighlightAnalysisRequest request) {
        return highlightService.createAnalysis(principal, assetId, request);
    }

    @GetMapping("/assets/{assetId}/highlight-analyses")
    public List<HighlightAnalysisSummary> listAnalyses(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID assetId) {
        return highlightService.listForAsset(principal, assetId);
    }

    @GetMapping("/highlight-analyses/{analysisId}")
    public HighlightAnalysisSummary getAnalysis(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID analysisId) {
        return highlightService.getFor(principal, analysisId);
    }

    @PostMapping("/highlight-candidates/{candidateId}/create-clip")
    public CreateClipResponse createClipFromCandidate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID candidateId) {
        return highlightService.createClipFromCandidate(principal, candidateId);
    }

    @PostMapping("/highlight-analyses/{analysisId}/selections")
    public HighlightSelectionSummary createSelection(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID analysisId, @RequestBody(required = false) CreateHighlightSelectionRequest request) {
        return selectionService.create(principal, analysisId, request);
    }

    @GetMapping("/highlight-analyses/{analysisId}/selections")
    public List<HighlightSelectionSummary> listSelections(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID analysisId) {
        return selectionService.list(principal, analysisId);
    }

    @GetMapping("/highlight-selections/{selectionId}")
    public HighlightSelectionSummary getSelection(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID selectionId) {
        return selectionService.get(principal, selectionId);
    }

    @PostMapping("/highlight-selections/{selectionId}/clips")
    public HighlightSelectionSummary createSelectionClips(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID selectionId) {
        return selectionService.createClips(principal, selectionId);
    }
}
