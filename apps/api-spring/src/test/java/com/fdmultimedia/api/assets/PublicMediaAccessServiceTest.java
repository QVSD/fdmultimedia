package com.fdmultimedia.api.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.publishing.PublicationRepository;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicMediaAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final PublicationRepository publications = mock(PublicationRepository.class);
    private final PublicMediaAccessService service = new PublicMediaAccessService(publications);

    private Workspace workspace;
    private AppUser owner;
    private MediaAsset asset;
    private SocialAccount instagramAccount;
    private Publication publication;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        asset = readyAsset();
        instagramAccount = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
        publication = new Publication(workspace, asset, instagramAccount, "caption", owner, NOW);
        publication.markPublishing(NOW);
    }

    @Test
    void resolvesStorageKeyWhilePublicationIsActivelyPublishing() {
        when(publications.findById(publication.getId())).thenReturn(Optional.of(publication));

        Optional<PublicMediaObject> resolved = service.resolve(new PublicMediaToken(publication.getId(), asset.getId(), 0));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().storageKey()).isEqualTo(asset.getStorageKey());
    }

    @Test
    void tokenStopsWorkingOncePublicationLeavesPublishingState() {
        when(publications.findById(publication.getId())).thenReturn(Optional.of(publication));
        publication.markPublished("req-1", "pub-1", NOW, NOW);

        Optional<PublicMediaObject> resolved = service.resolve(new PublicMediaToken(publication.getId(), asset.getId(), 0));

        assertThat(resolved).isEmpty();
    }

    @Test
    void tokenStopsWorkingAfterFailure() {
        when(publications.findById(publication.getId())).thenReturn(Optional.of(publication));
        publication.markFailed("CODE", "message", NOW);

        Optional<PublicMediaObject> resolved = service.resolve(new PublicMediaToken(publication.getId(), asset.getId(), 0));

        assertThat(resolved).isEmpty();
    }

    @Test
    void rejectsTokenWhoseAssetIdDoesNotMatchThePublicationsActualAsset() {
        when(publications.findById(publication.getId())).thenReturn(Optional.of(publication));

        Optional<PublicMediaObject> resolved = service.resolve(new PublicMediaToken(publication.getId(), UUID.randomUUID(), 0));

        assertThat(resolved).isEmpty();
    }

    @Test
    void rejectsNonInstagramPublications() {
        SocialAccount testAccount = new SocialAccount(workspace, SocialPlatform.TEST, "test account", owner, NOW);
        Publication testPublication = new Publication(workspace, asset, testAccount, "caption", owner, NOW);
        testPublication.markPublishing(NOW);
        when(publications.findById(testPublication.getId())).thenReturn(Optional.of(testPublication));

        Optional<PublicMediaObject> resolved = service.resolve(new PublicMediaToken(testPublication.getId(), asset.getId(), 0));

        assertThat(resolved).isEmpty();
    }

    @Test
    void rejectsUnknownPublicationId() {
        UUID unknown = UUID.randomUUID();
        when(publications.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.resolve(new PublicMediaToken(unknown, UUID.randomUUID(), 0))).isEmpty();
    }

    private MediaAsset readyAsset() {
        MediaAsset created = new MediaAsset(workspace, owner, "https://example.com/video.mp4", NOW);
        created.markImporting(NOW);
        created.markReady(new MediaImportMetadata("video.mp4", "video/mp4", 12_000, "0".repeat(64), null, null, null, null, null, "mp4"), "media-assets", "storage-key", NOW);
        return created;
    }
}
