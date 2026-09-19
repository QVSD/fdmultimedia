package com.fdmultimedia.api.contentsuggestions;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
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
public class ContentSuggestionController {

    private final ContentSuggestionService service;

    public ContentSuggestionController(ContentSuggestionService service) {
        this.service = service;
    }

    @PostMapping("/content-drafts/{draftId}/suggestions")
    public ContentSuggestionSummary create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID draftId,
            @Valid @RequestBody CreateContentSuggestionRequest request) {
        return service.create(principal, draftId, request);
    }

    @GetMapping("/content-drafts/{draftId}/suggestions")
    public List<ContentSuggestionSummary> list(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID draftId) {
        return service.listFor(principal, draftId);
    }

    @GetMapping("/content-suggestions/{suggestionId}")
    public ContentSuggestionSummary get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID suggestionId) {
        return service.getFor(principal, suggestionId);
    }

    @PostMapping("/content-suggestions/{suggestionId}/apply")
    public ContentSuggestionSummary apply(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID suggestionId) {
        return service.apply(principal, suggestionId);
    }

    @PostMapping("/content-suggestions/{suggestionId}/discard")
    public ContentSuggestionSummary discard(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID suggestionId) {
        return service.discard(principal, suggestionId);
    }
}
