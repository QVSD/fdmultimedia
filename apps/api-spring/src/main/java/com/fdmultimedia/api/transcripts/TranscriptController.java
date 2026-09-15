package com.fdmultimedia.api.transcripts;

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
public class TranscriptController {

    private final TranscriptService transcriptService;

    public TranscriptController(TranscriptService transcriptService) {
        this.transcriptService = transcriptService;
    }

    @PostMapping("/assets/{assetId}/transcripts")
    public MediaTranscriptSummary create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID assetId) {
        return transcriptService.createTranscript(principal, assetId);
    }

    @GetMapping("/assets/{assetId}/transcripts")
    public List<MediaTranscriptSummary> listForAsset(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID assetId) {
        return transcriptService.listForAsset(principal, assetId);
    }

    @GetMapping("/transcripts/{transcriptId}")
    public MediaTranscriptSummary get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID transcriptId) {
        return transcriptService.getFor(principal, transcriptId);
    }
}
