package com.fdmultimedia.api.publishing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.WorkerCredential;
import com.fdmultimedia.api.workers.WorkerRegistrationRequest;
import com.fdmultimedia.api.workspaces.Workspace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PublishingEligibilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final PublishingEligibilityService service = new PublishingEligibilityService();
    private final Workspace workspace = new Workspace("FD Multimedia", "fdm");
    private final AppUser owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
    private final Worker worker = new Worker(
            workspace,
            new WorkerCredential(UUID.randomUUID(), workspace, "local-agent", "$2a$10$hash"),
            new WorkerRegistrationRequest("machine-1", "Node A", "Windows 11", "amd64", "AMD Ryzen", 16, 34_359_738_368L, null, null, "fdm-worker/0.1.0"),
            NOW.minusSeconds(5));

    @Test
    void testPlatformOnlyNeedsTheBaselineGate() {
        MediaAsset asset = inspectedAsset(9, 16, 10_000, 100_000_000L, "mp4");

        assertThatCode(() -> service.validateAssetEligibility(asset, SocialPlatform.TEST)).doesNotThrowAnyException();
    }

    @Test
    void instagramAcceptsAssetWithinAllDocumentedBounds() {
        MediaAsset asset = inspectedAsset(1080, 1920, 10_000, 100_000_000L, "mp4");

        assertThatCode(() -> service.validateAssetEligibility(asset, SocialPlatform.INSTAGRAM)).doesNotThrowAnyException();
    }

    @Test
    void instagramRejectsTooShortDuration() {
        MediaAsset asset = inspectedAsset(1080, 1920, 1_000, 100_000_000L, "mp4");

        assertThatThrownBy(() -> service.validateAssetEligibility(asset, SocialPlatform.INSTAGRAM))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void instagramRejectsTooLongDuration() {
        MediaAsset asset = inspectedAsset(1080, 1920, 16L * 60 * 1000, 100_000_000L, "mp4");

        assertThatThrownBy(() -> service.validateAssetEligibility(asset, SocialPlatform.INSTAGRAM))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void instagramRejectsExtremeAspectRatio() {
        MediaAsset asset = inspectedAsset(5000, 100, 10_000, 100_000_000L, "mp4");

        assertThatThrownBy(() -> service.validateAssetEligibility(asset, SocialPlatform.INSTAGRAM))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void instagramRejectsOversizedFile() {
        MediaAsset asset = inspectedAsset(1080, 1920, 10_000, 400L * 1024 * 1024, "mp4");

        assertThatThrownBy(() -> service.validateAssetEligibility(asset, SocialPlatform.INSTAGRAM))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void instagramRejectsUnsupportedContainer() {
        MediaAsset asset = inspectedAsset(1080, 1920, 10_000, 100_000_000L, "avi");

        assertThatThrownBy(() -> service.validateAssetEligibility(asset, SocialPlatform.INSTAGRAM))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void instagramAcceptsMovContainer() {
        MediaAsset asset = inspectedAsset(1080, 1920, 10_000, 100_000_000L, "mov,mp4,m4a");

        assertThatCode(() -> service.validateAssetEligibility(asset, SocialPlatform.INSTAGRAM)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAssetMissingVideoRegardlessOfPlatform() {
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/audio.wav", NOW);
        asset.markImporting(NOW.minusSeconds(5));
        asset.markReady(new MediaImportMetadata("audio.wav", "audio/wav", 12_000, "0".repeat(64), null, null, null, null, null, "wav"), "media-assets", "storage-key", NOW.minusSeconds(4));
        Job inspectionJob = new Job(workspace, JobType.INSPECT_MEDIA, Map.of("assetId", asset.getId().toString()), 3, NOW.minusSeconds(3));
        inspectionJob.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        inspectionJob.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        asset.attachInspectionJob(inspectionJob, NOW.minusSeconds(2));
        asset.markInspecting(NOW.minusSeconds(1));
        asset.markInspected(new MediaInspectionMetadata(10_000L, null, null, null, "aac", "wav", null, 128_000L, false, true), NOW);

        assertThatThrownBy(() -> service.validateAssetEligibility(asset, SocialPlatform.TEST))
                .isInstanceOf(ResponseStatusException.class);
    }

    private MediaAsset inspectedAsset(int width, int height, long durationMs, long fileSizeBytes, String containerFormat) {
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        asset.markImporting(NOW.minusSeconds(5));
        asset.markReady(new MediaImportMetadata("media.mp4", "video/mp4", fileSizeBytes, "0".repeat(64), null, null, null, null, null, containerFormat), "media-assets", "storage-key", NOW.minusSeconds(4));
        Job inspectionJob = new Job(workspace, JobType.INSPECT_MEDIA, Map.of("assetId", asset.getId().toString()), 3, NOW.minusSeconds(3));
        inspectionJob.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        inspectionJob.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        asset.attachInspectionJob(inspectionJob, NOW.minusSeconds(2));
        asset.markInspecting(NOW.minusSeconds(1));
        asset.markInspected(new MediaInspectionMetadata(
                durationMs, width, height, "h264", "aac", containerFormat,
                new BigDecimal("29.970"), 800_000L, true, true), NOW);
        return asset;
    }
}
