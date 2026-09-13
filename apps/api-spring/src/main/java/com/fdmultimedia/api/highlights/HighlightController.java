package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.assets.CreateClipResponse;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HighlightController {

    private final HighlightService highlightService;

    public HighlightController(HighlightService highlightService) {
        this.highlightService = highlightService;
    }

    @PostMapping("/assets/{assetId}/highlight-analyses")
    public HighlightAnalysisSummary createAnalysis(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID assetId) {
        return highlightService.createAnalysis(principal, assetId);
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
}
