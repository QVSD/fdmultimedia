package com.fdmultimedia.api.publishing;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/publications")
public class PublicationController {

    private final PublishingService publishingService;

    public PublicationController(PublishingService publishingService) {
        this.publishingService = publishingService;
    }

    @GetMapping
    public List<PublicationSummary> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID assetId) {
        return publishingService.listFor(principal, assetId);
    }

    @GetMapping("/{publicationId}")
    public PublicationSummary get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID publicationId) {
        return publishingService.getFor(principal, publicationId);
    }
}
