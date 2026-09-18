package com.fdmultimedia.api.contentdrafts;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/content-drafts")
public class ContentDraftController {

    private final ContentDraftService contentDraftService;

    public ContentDraftController(ContentDraftService contentDraftService) {
        this.contentDraftService = contentDraftService;
    }

    @GetMapping
    public List<ContentDraftSummary> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return contentDraftService.listFor(principal);
    }

    @PostMapping
    public ContentDraftSummary create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateContentDraftRequest request) {
        return contentDraftService.createFromAsset(principal, request);
    }

    @GetMapping("/{draftId}")
    public ContentDraftSummary get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID draftId) {
        return contentDraftService.getFor(principal, draftId);
    }

    @PatchMapping("/{draftId}")
    public ContentDraftSummary update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID draftId,
            @RequestBody UpdateContentDraftRequest request) {
        return contentDraftService.update(principal, draftId, request);
    }

    @PostMapping("/from-highlight/{candidateId}")
    public ContentDraftSummary createFromHighlight(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID candidateId) {
        return contentDraftService.createFromHighlightCandidate(principal, candidateId);
    }

    @PostMapping("/{draftId}/publish")
    public ContentDraftSummary publish(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID draftId,
            @Valid @RequestBody PublishContentDraftRequest request) {
        return contentDraftService.publish(principal, draftId, request);
    }

    @PostMapping("/{draftId}/retry-preparation")
    public ContentDraftSummary retryPreparation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID draftId) {
        return contentDraftService.retryPreparation(principal, draftId);
    }
}
