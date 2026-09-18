package com.fdmultimedia.api.contentsources;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/content-sources")
public class ContentSourceController {

    private final ContentSourceService service;

    public ContentSourceController(ContentSourceService service) {
        this.service = service;
    }

    @GetMapping
    public List<ContentSourceSummary> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.list(principal);
    }

    @PostMapping
    public ContentSourceSummary create(@AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody CreateContentSourceRequest request) {
        return service.create(principal, request);
    }

    @GetMapping("/{sourceId}")
    public ContentSourceSummary get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID sourceId) {
        return service.getFor(principal, sourceId);
    }

    @PatchMapping("/{sourceId}")
    public ContentSourceSummary update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sourceId,
            @Valid @RequestBody UpdateContentSourceRequest request) {
        return service.update(principal, sourceId, request);
    }

    @PostMapping("/{sourceId}/pause")
    public ContentSourceSummary pause(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID sourceId) {
        return service.pause(principal, sourceId);
    }

    @PostMapping("/{sourceId}/resume")
    public ContentSourceSummary resume(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID sourceId) {
        return service.resume(principal, sourceId);
    }

    @GetMapping("/{sourceId}/assets")
    public List<ContentSourceAssetSummary> listAssets(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID sourceId) {
        return service.listAssets(principal, sourceId);
    }

    @PostMapping("/{sourceId}/assets")
    public ContentSourceAssetSummary addAsset(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sourceId,
            @Valid @RequestBody AddContentSourceAssetRequest request) {
        return service.addAsset(principal, sourceId, request);
    }

    @DeleteMapping("/{sourceId}/assets/{assetId}")
    public void removeAsset(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sourceId,
            @PathVariable UUID assetId) {
        service.removeAsset(principal, sourceId, assetId);
    }
}
