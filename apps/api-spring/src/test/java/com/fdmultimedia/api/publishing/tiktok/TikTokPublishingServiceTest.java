package com.fdmultimedia.api.publishing.tiktok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
import com.fdmultimedia.api.assets.ObjectStorageService;
import com.fdmultimedia.api.assets.StorageAccess;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.publishing.PublicationProviderState;
import com.fdmultimedia.api.publishing.PublicationProviderStateRepository;
import com.fdmultimedia.api.publishing.instagram.InstagramDriveOutcome;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TikTokPublishingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private static final byte[] VIDEO_BYTES = "not-a-real-video-but-the-right-length!!".getBytes(StandardCharsets.UTF_8);

    private final TikTokApiClient api = mock(TikTokApiClient.class);
    private final TikTokAccountConnectionService connection = mock(TikTokAccountConnectionService.class);
    private final TikTokPublicationSettingsRepository settingsRepository = mock(TikTokPublicationSettingsRepository.class);
    private final PublicationProviderStateRepository states = mock(PublicationProviderStateRepository.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final TikTokProperties properties = new TikTokProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TikTokPublishingService service =
            new TikTokPublishingService(api, connection, settingsRepository, states, storage, properties, clock);

    private HttpServer sourceServer;
    private Publication publication;
    private SocialAccount account;
    private MediaAsset asset;
    private TikTokPublicationSettings settings;
    private TikTokModels.CreatorInfo creatorInfo;

    @BeforeEach
    void setUp() throws Exception {
        Workspace workspace = new Workspace("FD Multimedia", "fdm");
        AppUser owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        account = new SocialAccount(workspace, SocialPlatform.TIKTOK, "creator", "tiktok-open-id-1", owner, NOW);
        asset = new MediaAsset(workspace, owner, "https://example.com/video.mp4", NOW);
        asset.markImporting(NOW);
        asset.markReady(new MediaImportMetadata("video.mp4", "video/mp4", VIDEO_BYTES.length, "sha", 15_000L, 1080, 1920, "h264", "aac", "mp4"),
                "media-assets", "workspace/video.mp4", NOW);
        asset.attachInspectionJob(null, NOW);
        asset.markInspecting(NOW);
        asset.markInspected(new MediaInspectionMetadata(15_000L, 1080, 1920, "h264", "aac", "mp4",
                BigDecimal.valueOf(30), 4_000_000L, true, true), NOW);
        publication = new Publication(workspace, asset, account, "caption", owner, NOW);
        settings = new TikTokPublicationSettings(publication, new TikTokSettingsRequest("SELF_ONLY", false, false, false), NOW);
        when(settingsRepository.findByPublication(publication)).thenReturn(Optional.of(settings));
        when(connection.validAccessToken(account)).thenReturn("decrypted-access-token");
        when(states.saveAndFlush(any(PublicationProviderState.class))).thenAnswer(inv -> inv.getArgument(0));
        creatorInfo = new TikTokModels.CreatorInfo("dragos", "Dragos", java.util.List.of("SELF_ONLY"), false, false, false, 600);

        sourceServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sourceServer.createContext("/source", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, VIDEO_BYTES.length);
            exchange.getResponseBody().write(VIDEO_BYTES);
            exchange.close();
        });
        sourceServer.start();
        when(storage.presignedGet("workspace/video.mp4")).thenReturn(
                new StorageAccess("http://127.0.0.1:" + sourceServer.getAddress().getPort() + "/source",
                        "media-assets", "workspace/video.mp4", NOW.plusSeconds(300)));
    }

    @AfterEach
    void tearDown() {
        sourceServer.stop(0);
    }

    @Test
    void firstDrivePersistsThePublishIdBeforeUploadingSoARestartCanNeverReInitialize() throws Exception {
        // The publish_id must be durable the moment TikTok confirms it, before any upload byte is
        // sent — otherwise a crash mid-upload would have no way to resume without risking a
        // duplicate post. initialize() and upload() happen within the same drive() call when
        // starting fresh, so by the time drive() returns we only get to observe the *final*
        // state; the interaction order below is what proves persistence came before the upload.
        when(states.findByPublication(publication)).thenReturn(Optional.empty());
        when(api.creatorInfo("decrypted-access-token")).thenReturn(creatorInfo);
        when(api.initialize(eq("decrypted-access-token"), eq(settings), eq("caption"), eq((long) VIDEO_BYTES.length)))
                .thenReturn(new TikTokModels.Init("publish-1", "https://open-upload.tiktokapis.com/up"));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.IN_PROGRESS);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(states, api);
        order.verify(states).saveAndFlush(any(PublicationProviderState.class));
        order.verify(api).upload(eq("https://open-upload.tiktokapis.com/up"), any(), eq("video/mp4"));
        ArgumentCaptor<PublicationProviderState> saved = ArgumentCaptor.forClass(PublicationProviderState.class);
        verify(states).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getProviderContainerId()).isEqualTo("publish-1");
        // markProcessing() runs after the (mocked, successful) upload and clears the upload URL.
        assertThat(saved.getValue().getState()).isEqualTo("PROCESSING");
        assertThat(saved.getValue().getProviderUploadUrl()).isNull();
    }

    @Test
    void missingSettingsFailsTerminallyBeforeCallingTikTok() {
        when(settingsRepository.findByPublication(publication)).thenReturn(Optional.empty());

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("MEDIA_NOT_ELIGIBLE");
        assertThat(outcome.terminal()).isTrue();
        verifyNoInteractions(api);
    }

    @Test
    void privacyOptionNoLongerAvailableFailsTerminallyWithoutInitializing() {
        when(states.findByPublication(publication)).thenReturn(Optional.empty());
        TikTokModels.CreatorInfo restricted = new TikTokModels.CreatorInfo("dragos", "Dragos", java.util.List.of("PUBLIC_TO_EVERYONE"), false, false, false, 600);
        when(api.creatorInfo("decrypted-access-token")).thenReturn(restricted);

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("PRIVACY_OPTION_INVALID");
        assertThat(outcome.terminal()).isTrue();
        verify(api, never()).initialize(any(), any(), any(), anyLong());
    }

    @Test
    void ambiguousInitializationOutcomeIsNeverRetriedBlindly() {
        when(states.findByPublication(publication)).thenReturn(Optional.empty());
        when(api.creatorInfo("decrypted-access-token")).thenReturn(creatorInfo);
        when(api.initialize(any(), any(), any(), anyLong()))
                .thenThrow(new TikTokApiException("PROVIDER_OUTCOME_UNKNOWN", "unknown", false, true));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
        // terminal=true here means "stop automatic retries, this needs manual review" —
        // not "definitely failed" — since blindly retrying an ambiguous init could double-post.
        assertThat(outcome.terminal()).isTrue();
        verify(states, never()).saveAndFlush(any());
    }

    @Test
    void restartAfterPublishIdPersistedResumesUploadWithoutReInitializing() throws Exception {
        PublicationProviderState existing = new PublicationProviderState(publication, "TIKTOK", "publish-1", "CONTAINER_CREATED", NOW.minusSeconds(30));
        existing.prepareUpload("https://open-upload.tiktokapis.com/resume", NOW.minusSeconds(30));
        when(states.findByPublication(publication)).thenReturn(Optional.of(existing));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.IN_PROGRESS);
        assertThat(existing.getState()).isEqualTo("PROCESSING");
        assertThat(existing.getProviderUploadUrl()).isNull();
        verify(api, never()).creatorInfo(any());
        verify(api, never()).initialize(any(), any(), any(), anyLong());
        verify(api).upload(eq("https://open-upload.tiktokapis.com/resume"), any(), eq("video/mp4"));
    }

    @Test
    void uploadFailureMarksOutcomeUnknownRatherThanRetryingBlindly() throws Exception {
        PublicationProviderState existing = new PublicationProviderState(publication, "TIKTOK", "publish-1", "CONTAINER_CREATED", NOW.minusSeconds(30));
        existing.prepareUpload("https://open-upload.tiktokapis.com/resume", NOW.minusSeconds(30));
        when(states.findByPublication(publication)).thenReturn(Optional.of(existing));
        doThrow(new TikTokApiException("PROVIDER_TEMPORARY", "network reset", true))
                .when(api).upload(anyString(), any(), anyString());

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
        // Never auto-retried: TikTok may have already accepted the upload bytes.
        assertThat(outcome.terminal()).isTrue();
        assertThat(existing.getState()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(existing.getProviderUploadUrl()).isNull();
    }

    @Test
    void processingStatusCompletePublishesAndPersistsThePublicPostId() {
        PublicationProviderState existing = new PublicationProviderState(publication, "TIKTOK", "publish-1", "PROCESSING", NOW.minusSeconds(30));
        when(states.findByPublication(publication)).thenReturn(Optional.of(existing));
        when(api.status("decrypted-access-token", "publish-1"))
                .thenReturn(new TikTokModels.Status("PUBLISH_COMPLETE", "7100000000000000000", null));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.PUBLISHED);
        assertThat(outcome.providerRequestId()).isEqualTo("publish-1");
        assertThat(outcome.providerPublicationId()).isEqualTo("7100000000000000000");
        assertThat(existing.getState()).isEqualTo("PUBLISHED");
    }

    @Test
    void processingStatusFailedIsTerminal() {
        PublicationProviderState existing = new PublicationProviderState(publication, "TIKTOK", "publish-1", "PROCESSING", NOW.minusSeconds(30));
        when(states.findByPublication(publication)).thenReturn(Optional.of(existing));
        when(api.status("decrypted-access-token", "publish-1"))
                .thenReturn(new TikTokModels.Status("FAILED", null, "video_too_long"));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("PROVIDER_PROCESSING_FAILED");
        assertThat(outcome.terminal()).isTrue();
    }

    @Test
    void processingStatusStillProcessingStaysInProgressAndPersistsNoTerminalState() {
        PublicationProviderState existing = new PublicationProviderState(publication, "TIKTOK", "publish-1", "PROCESSING", NOW.minusSeconds(30));
        when(states.findByPublication(publication)).thenReturn(Optional.of(existing));
        when(api.status("decrypted-access-token", "publish-1"))
                .thenReturn(new TikTokModels.Status("PROCESSING_DOWNLOAD", null, null));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.IN_PROGRESS);
        assertThat(existing.getState()).isEqualTo("PROCESSING");
    }

    @Test
    void alreadyPublishedShortCircuitsWithoutCallingTikTokAgain() {
        PublicationProviderState existing = new PublicationProviderState(publication, "TIKTOK", "publish-1", "CONTAINER_CREATED", NOW.minusSeconds(60));
        existing.markPublished("7100000000000000000", NOW.minusSeconds(10));
        when(states.findByPublication(publication)).thenReturn(Optional.of(existing));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.PUBLISHED);
        assertThat(outcome.providerPublicationId()).isEqualTo("7100000000000000000");
        verifyNoInteractions(api);
    }

    @Test
    void outcomeUnknownRequiresManualReconciliationRatherThanAutomaticRetry() {
        PublicationProviderState existing = new PublicationProviderState(publication, "TIKTOK", "publish-1", "OUTCOME_UNKNOWN", NOW.minusSeconds(60));
        when(states.findByPublication(publication)).thenReturn(Optional.of(existing));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
        assertThat(outcome.terminal()).isTrue();
        verifyNoInteractions(api);
    }
}
