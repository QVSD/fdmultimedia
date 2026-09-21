package com.fdmultimedia.api.publishing;

import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.assets.MediaInspectionStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Platform-aware publish eligibility. TEST only needs the baseline
 * READY + INSPECTED + video gate that Phase 10A already enforced. Instagram
 * additionally validates against the currently documented Reels requirements
 * (Meta Graph API reference, {@code ig-user/media}, fetched 2026-09 — see
 * docs/ARCHITECTURE.md) using only metadata the inspection step already
 * captured, so an obviously-incompatible asset is rejected before any
 * external call is made. Requirements this service cannot determine from
 * existing inspection metadata (e.g. exact bitrate/GOP structure) are left
 * for the provider's own validation at container-creation time.
 */
@Service
public class PublishingEligibilityService {

    private static final long INSTAGRAM_MIN_DURATION_MS = 3_000L;
    private static final long INSTAGRAM_MAX_DURATION_MS = 15L * 60 * 1000;
    private static final double INSTAGRAM_MIN_ASPECT_RATIO = 0.1;
    private static final double INSTAGRAM_MAX_ASPECT_RATIO = 10.0;
    private static final long INSTAGRAM_MAX_FILE_SIZE_BYTES = 300L * 1024 * 1024;

    public void validateAssetEligibility(MediaAsset asset, SocialPlatform platform) {
        if (asset.getStatus() != MediaAssetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not ready");
        }
        if (asset.getInspectionStatus() != MediaInspectionStatus.INSPECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not inspected");
        }
        if (!Boolean.TRUE.equals(asset.getHasVideo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset must contain video");
        }
        if (platform == SocialPlatform.INSTAGRAM) {
            validateInstagramEligibility(asset);
        } else if (platform == SocialPlatform.TIKTOK) {
            String container = asset.getContainerFormat();
            String codec = asset.getVideoCodec();
            if (container != null && java.util.stream.Stream.of("mp4", "mov", "webm").noneMatch(v -> container.toLowerCase(java.util.Locale.ROOT).contains(v))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "TikTok requires MP4, MOV, or WebM video");
            }
            if (codec != null && java.util.stream.Stream.of("h264", "hevc", "h265", "vp8", "vp9").noneMatch(v -> codec.toLowerCase(java.util.Locale.ROOT).contains(v))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "TikTok video codec is not supported");
            }
        }
    }

    private void validateInstagramEligibility(MediaAsset asset) {
        Long durationMs = asset.getDurationMs();
        if (durationMs == null || durationMs < INSTAGRAM_MIN_DURATION_MS || durationMs > INSTAGRAM_MAX_DURATION_MS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Instagram Reels require a duration between 3 seconds and 15 minutes");
        }
        Integer width = asset.getWidth();
        Integer height = asset.getHeight();
        if (width == null || height == null || width <= 0 || height <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset resolution is unknown");
        }
        double aspectRatio = (double) width / (double) height;
        if (aspectRatio < INSTAGRAM_MIN_ASPECT_RATIO || aspectRatio > INSTAGRAM_MAX_ASPECT_RATIO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset aspect ratio is outside Instagram's supported range");
        }
        Long fileSizeBytes = asset.getFileSizeBytes();
        if (fileSizeBytes != null && fileSizeBytes > INSTAGRAM_MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset exceeds Instagram's 300 MB file size limit");
        }
        String container = asset.getContainerFormat();
        if (container != null && !container.toLowerCase(java.util.Locale.ROOT).contains("mp4")
                && !container.toLowerCase(java.util.Locale.ROOT).contains("mov")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instagram requires an MP4 or MOV container");
        }
    }
}
