package com.fdmultimedia.api.assets;

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
@RequestMapping("/api/assets")
public class MediaAssetController {

    private final MediaAssetService mediaAssetService;

    public MediaAssetController(MediaAssetService mediaAssetService) {
        this.mediaAssetService = mediaAssetService;
    }

    @PostMapping("/import")
    public MediaImportResponse createImport(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody MediaImportRequest request) {
        return mediaAssetService.createImport(principal, request);
    }

    @GetMapping
    public List<MediaAssetSummary> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return mediaAssetService.listFor(principal);
    }

    @GetMapping("/{assetId}")
    public MediaAssetSummary get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID assetId) {
        return mediaAssetService.getFor(principal, assetId);
    }

    @GetMapping("/{assetId}/download-url")
    public DownloadUrlResponse downloadUrl(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID assetId) {
        return mediaAssetService.downloadUrl(principal, assetId);
    }
}
