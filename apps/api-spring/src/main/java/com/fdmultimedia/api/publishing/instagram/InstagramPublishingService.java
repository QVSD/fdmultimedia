package com.fdmultimedia.api.publishing.instagram;

import com.fdmultimedia.api.accounts.CredentialUnavailableException;
import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialCredentialService;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.PublicMediaTokenService;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.publishing.PublicationProviderState;
import com.fdmultimedia.api.publishing.PublicationProviderStateRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Instagram "Publishing Coordinator": every credential-bearing step of
 * turning a Publication into a real Instagram Reel lives here, entirely on
 * the backend. The Worker never sees this class or a token — it only calls
 * the narrow {@code /instagram/drive} endpoint repeatedly, and this service
 * decides, one bounded step at a time, what that call actually does.
 *
 * <p>Reconciliation: before creating a media container, this checks
 * {@link PublicationProviderStateRepository} for an existing one on this
 * Publication and reuses it rather than creating a duplicate external post.
 * The container id (and, as soon as it exists, the final media id) is
 * persisted immediately so a crash between "Meta confirms X" and "our own
 * completion bookkeeping" loses at most that narrow window, never causes a
 * second real post.
 */
@Service
public class InstagramPublishingService {

    private final InstagramGraphClient graphClient;
    private final SocialCredentialService credentialService;
    private final PublicationProviderStateRepository providerStates;
    private final PublicMediaTokenService mediaTokenService;
    private final InstagramProperties properties;
    private final Clock clock;

    public InstagramPublishingService(
            InstagramGraphClient graphClient,
            SocialCredentialService credentialService,
            PublicationProviderStateRepository providerStates,
            PublicMediaTokenService mediaTokenService,
            InstagramProperties properties,
            Clock clock) {
        this.graphClient = graphClient;
        this.credentialService = credentialService;
        this.providerStates = providerStates;
        this.mediaTokenService = mediaTokenService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public InstagramDriveOutcome drive(Publication publication) {
        SocialAccount account = publication.getSocialAccount();
        String accessToken;
        try {
            accessToken = credentialService.decryptAccessToken(account);
        } catch (CredentialUnavailableException ex) {
            return InstagramDriveOutcome.failure(InstagramErrorCodes.CREDENTIAL_EXPIRED, "Instagram credential is missing or expired; reconnect the account", true);
        }

        PublicationProviderState state = providerStates.findByPublication(publication).orElse(null);
        Instant now = Instant.now(clock);

        if (state == null) {
            String containerId;
            try {
                String mediaUrl = buildPublicMediaUrl(publication, publication.getAsset());
                containerId = graphClient.createMediaContainer(account.getExternalAccountId(), accessToken, mediaUrl, publication.getCaption());
            } catch (InstagramApiException ex) {
                return outcomeFor(ex);
            }
            state = providerStates.save(new PublicationProviderState(publication, "INSTAGRAM", containerId, "CONTAINER_CREATED", now));
        }

        if ("PUBLISHED".equals(state.getState())) {
            // A prior drive call already confirmed publish; return the durable
            // result again rather than calling the provider a second time.
            return InstagramDriveOutcome.published(state.getProviderContainerId(), state.getProviderMediaId());
        }

        if (now.isAfter(state.getCreatedAt().plus(properties.getContainerProcessingTimeout()))) {
            return InstagramDriveOutcome.failure(InstagramErrorCodes.PROCESSING_TIMEOUT, "Instagram did not finish processing the media in time", false);
        }

        InstagramContainerStatus status;
        try {
            status = graphClient.fetchContainerStatus(state.getProviderContainerId(), accessToken);
        } catch (InstagramApiException ex) {
            return outcomeFor(ex);
        }

        return switch (status) {
            case IN_PROGRESS -> InstagramDriveOutcome.inProgress();
            case ERROR -> InstagramDriveOutcome.failure(InstagramErrorCodes.MEDIA_REJECTED, "Instagram rejected the media during processing", true);
            case EXPIRED -> InstagramDriveOutcome.failure(InstagramErrorCodes.PROCESSING_TIMEOUT, "Instagram container expired before it was published", true);
            case FINISHED -> publish(publication, account, accessToken, state, now);
            case PUBLISHED -> {
                // The container itself reports PUBLISHED (e.g. a retry after our
                // own publish call succeeded but this instance crashed before
                // persisting it) but our local media id is unknown. This is the
                // one gap the official API leaves us: there is no documented way
                // to recover the exact media id after the fact from the
                // container alone, so the Publication is completed without one
                // rather than risking a second media_publish call.
                state.markPublished(null, now);
                yield InstagramDriveOutcome.published(state.getProviderContainerId(), null);
            }
        };
    }

    private InstagramDriveOutcome publish(Publication publication, SocialAccount account, String accessToken, PublicationProviderState state, Instant now) {
        try {
            String mediaId = graphClient.publishContainer(account.getExternalAccountId(), accessToken, state.getProviderContainerId());
            state.markPublished(mediaId, now);
            return InstagramDriveOutcome.published(state.getProviderContainerId(), mediaId);
        } catch (InstagramApiException ex) {
            return outcomeFor(ex);
        }
    }

    private String buildPublicMediaUrl(Publication publication, MediaAsset asset) {
        String token = mediaTokenService.issue(publication.getId(), asset.getId(), properties.getPublicMediaUrlTtl());
        return properties.getPublicBaseUrl() + "/api/public-media/" + token;
    }

    private InstagramDriveOutcome outcomeFor(InstagramApiException ex) {
        return InstagramDriveOutcome.failure(ex.code(), ex.getMessage(), !ex.retryable());
    }
}
