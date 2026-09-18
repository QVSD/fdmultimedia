package com.fdmultimedia.api.publishing.instagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.CredentialUnavailableException;
import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialCredentialService;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.PublicMediaTokenService;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.publishing.PublicationProviderState;
import com.fdmultimedia.api.publishing.PublicationProviderStateRepository;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InstagramPublishingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final InstagramGraphClient graphClient = mock(InstagramGraphClient.class);
    private final SocialCredentialService credentialService = mock(SocialCredentialService.class);
    private final PublicationProviderStateRepository providerStates = mock(PublicationProviderStateRepository.class);
    private final PublicMediaTokenService mediaTokenService = mock(PublicMediaTokenService.class);
    private final InstagramProperties properties = new InstagramProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InstagramPublishingService service =
            new InstagramPublishingService(graphClient, credentialService, providerStates, mediaTokenService, properties, clock);

    private Publication publication;
    private SocialAccount account;

    @BeforeEach
    void setUp() {
        Workspace workspace = new Workspace("FD Multimedia", "fdm");
        AppUser owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        account = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/video.mp4", NOW);
        publication = new Publication(workspace, asset, account, "caption", owner, NOW);
        when(credentialService.decryptAccessToken(account)).thenReturn("decrypted-token");
        when(mediaTokenService.issue(any(), any(), any())).thenReturn("signed-token");
        when(providerStates.save(any(PublicationProviderState.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsContainerOnFirstDriveAndReportsInProgress() {
        when(providerStates.findByPublication(publication)).thenReturn(Optional.empty());
        when(graphClient.createMediaContainer(eq("ig-user-1"), eq("decrypted-token"), anyString(), anyString())).thenReturn("container-1");
        when(graphClient.fetchContainerStatus("container-1", "decrypted-token")).thenReturn(InstagramContainerStatus.IN_PROGRESS);

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.IN_PROGRESS);
        ArgumentCaptor<PublicationProviderState> saved = ArgumentCaptor.forClass(PublicationProviderState.class);
        verify(providerStates).save(saved.capture());
        assertThat(saved.getValue().getProviderContainerId()).isEqualTo("container-1");
        assertThat(saved.getValue().getState()).isEqualTo("CONTAINER_CREATED");
    }

    @Test
    void reusesExistingContainerOnRetryRatherThanCreatingADuplicate() {
        PublicationProviderState existing = new PublicationProviderState(publication, "INSTAGRAM", "container-1", "CONTAINER_CREATED", NOW.minusSeconds(30));
        when(providerStates.findByPublication(publication)).thenReturn(Optional.of(existing));
        when(graphClient.fetchContainerStatus("container-1", "decrypted-token")).thenReturn(InstagramContainerStatus.IN_PROGRESS);

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.IN_PROGRESS);
        verify(graphClient, never()).createMediaContainer(any(), any(), any(), any());
    }

    @Test
    void publishesOnceContainerFinishesAndPersistsMediaIdImmediately() {
        PublicationProviderState existing = new PublicationProviderState(publication, "INSTAGRAM", "container-1", "CONTAINER_CREATED", NOW.minusSeconds(30));
        when(providerStates.findByPublication(publication)).thenReturn(Optional.of(existing));
        when(graphClient.fetchContainerStatus("container-1", "decrypted-token")).thenReturn(InstagramContainerStatus.FINISHED);
        when(graphClient.publishContainer("ig-user-1", "decrypted-token", "container-1")).thenReturn("media-1");

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.PUBLISHED);
        assertThat(outcome.providerRequestId()).isEqualTo("container-1");
        assertThat(outcome.providerPublicationId()).isEqualTo("media-1");
        assertThat(existing.getState()).isEqualTo("PUBLISHED");
        assertThat(existing.getProviderMediaId()).isEqualTo("media-1");
    }

    @Test
    void repeatedDriveAfterPublishReturnsSameResultWithoutCallingProviderAgain() {
        PublicationProviderState alreadyPublished = new PublicationProviderState(publication, "INSTAGRAM", "container-1", "CONTAINER_CREATED", NOW.minusSeconds(60));
        alreadyPublished.markPublished("media-1", NOW.minusSeconds(10));
        when(providerStates.findByPublication(publication)).thenReturn(Optional.of(alreadyPublished));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.PUBLISHED);
        assertThat(outcome.providerPublicationId()).isEqualTo("media-1");
        verifyNoInteractions(graphClient);
    }

    @Test
    void containerReportingPublishedWithoutLocalRecordReconcilesWithoutDuplicatePublish() {
        // Simulates a crash between a successful media_publish call and our
        // own bookkeeping: our local state still says CONTAINER_CREATED, but
        // asking the provider about the container shows it already went out.
        PublicationProviderState existing = new PublicationProviderState(publication, "INSTAGRAM", "container-1", "CONTAINER_CREATED", NOW.minusSeconds(30));
        when(providerStates.findByPublication(publication)).thenReturn(Optional.of(existing));
        when(graphClient.fetchContainerStatus("container-1", "decrypted-token")).thenReturn(InstagramContainerStatus.PUBLISHED);

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.PUBLISHED);
        assertThat(existing.getState()).isEqualTo("PUBLISHED");
        verify(graphClient, never()).publishContainer(any(), any(), any());
    }

    @Test
    void expiredCredentialFailsTerminallyWithoutCallingProvider() {
        when(credentialService.decryptAccessToken(account)).thenThrow(new CredentialUnavailableException("expired"));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(InstagramErrorCodes.CREDENTIAL_EXPIRED);
        assertThat(outcome.terminal()).isTrue();
        verifyNoInteractions(graphClient);
    }

    @Test
    void processingTimeoutFailsTerminallyAfterConfiguredDuration() {
        properties.setContainerProcessingTimeout(Duration.ofMinutes(5));
        PublicationProviderState existing = new PublicationProviderState(
                publication, "INSTAGRAM", "container-1", "CONTAINER_CREATED", NOW.minus(Duration.ofMinutes(10)));
        when(providerStates.findByPublication(publication)).thenReturn(Optional.of(existing));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(InstagramErrorCodes.PROCESSING_TIMEOUT);
        verify(graphClient, never()).fetchContainerStatus(any(), any());
    }

    @Test
    void containerErrorStatusFailsTerminally() {
        PublicationProviderState existing = new PublicationProviderState(publication, "INSTAGRAM", "container-1", "CONTAINER_CREATED", NOW.minusSeconds(30));
        when(providerStates.findByPublication(publication)).thenReturn(Optional.of(existing));
        when(graphClient.fetchContainerStatus("container-1", "decrypted-token")).thenReturn(InstagramContainerStatus.ERROR);

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(InstagramErrorCodes.MEDIA_REJECTED);
        assertThat(outcome.terminal()).isTrue();
    }

    @Test
    void rateLimitedStatusCheckIsRetryableNotTerminal() {
        PublicationProviderState existing = new PublicationProviderState(publication, "INSTAGRAM", "container-1", "CONTAINER_CREATED", NOW.minusSeconds(30));
        when(providerStates.findByPublication(publication)).thenReturn(Optional.of(existing));
        when(graphClient.fetchContainerStatus("container-1", "decrypted-token"))
                .thenThrow(new InstagramApiException(InstagramErrorCodes.RATE_LIMITED, "rate limited", true));

        InstagramDriveOutcome outcome = service.drive(publication);

        assertThat(outcome.status()).isEqualTo(InstagramDriveOutcome.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(InstagramErrorCodes.RATE_LIMITED);
        assertThat(outcome.terminal()).isFalse();
    }

}
