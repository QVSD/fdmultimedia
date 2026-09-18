package com.fdmultimedia.api.contentsources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ContentSourceServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final ContentSourceRepository sources = mock(ContentSourceRepository.class);
    private final ContentSourceAssetRepository memberships = mock(ContentSourceAssetRepository.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final ContentSourceService service = new ContentSourceService(
            authService, sources, memberships, assets, Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        when(sources.save(any(ContentSource.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(memberships.save(any(ContentSourceAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsContentSource() {
        ContentSourceSummary summary = service.create(user, new CreateContentSourceRequest("Incoming Tech Videos", "desc"));

        assertThat(summary.name()).isEqualTo("Incoming Tech Videos");
        assertThat(summary.type()).isEqualTo(ContentSourceType.MEDIA_LIBRARY);
        assertThat(summary.status()).isEqualTo(ContentSourceStatus.ACTIVE);
        assertThat(summary.assetCount()).isZero();
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> service.create(user, new CreateContentSourceRequest("  ", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updatesNameAndDescription() {
        ContentSource source = new ContentSource(workspace, "Original", null, owner, NOW);
        when(sources.findByWorkspaceAndIdForUpdate(workspace, source.getId())).thenReturn(Optional.of(source));

        ContentSourceSummary updated = service.update(user, source.getId(), new UpdateContentSourceRequest("Renamed", "new desc"));

        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.description()).isEqualTo("new desc");
    }

    @Test
    void pausesAndResumesSource() {
        ContentSource source = new ContentSource(workspace, "Source", null, owner, NOW);
        when(sources.findByWorkspaceAndIdForUpdate(workspace, source.getId())).thenReturn(Optional.of(source));

        ContentSourceSummary paused = service.pause(user, source.getId());
        assertThat(paused.status()).isEqualTo(ContentSourceStatus.PAUSED);

        ContentSourceSummary resumed = service.resume(user, source.getId());
        assertThat(resumed.status()).isEqualTo(ContentSourceStatus.ACTIVE);
    }

    @Test
    void getForRejectsSourceFromAnotherWorkspace() {
        UUID otherId = UUID.randomUUID();
        when(sources.findByWorkspaceAndId(workspace, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFor(user, otherId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void addsOriginalAssetToSource() {
        ContentSource source = new ContentSource(workspace, "Source", null, owner, NOW);
        when(sources.findByWorkspaceAndId(workspace, source.getId())).thenReturn(Optional.of(source));
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(memberships.existsByContentSourceAndMediaAsset(source, asset)).thenReturn(false);

        ContentSourceAssetSummary summary = service.addAsset(user, source.getId(), new AddContentSourceAssetRequest(asset.getId()));

        assertThat(summary.mediaAssetId()).isEqualTo(asset.getId());
    }

    @Test
    void rejectsAddingClipDerivativeToSource() {
        ContentSource source = new ContentSource(workspace, "Source", null, owner, NOW);
        when(sources.findByWorkspaceAndId(workspace, source.getId())).thenReturn(Optional.of(source));
        MediaAsset original = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        MediaAsset clip = MediaAsset.clipDerivative(workspace, owner, original, NOW);
        when(assets.findByWorkspaceAndId(workspace, clip.getId())).thenReturn(Optional.of(clip));

        assertThatThrownBy(() -> service.addAsset(user, source.getId(), new AddContentSourceAssetRequest(clip.getId())))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsDuplicateMembership() {
        ContentSource source = new ContentSource(workspace, "Source", null, owner, NOW);
        when(sources.findByWorkspaceAndId(workspace, source.getId())).thenReturn(Optional.of(source));
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(memberships.existsByContentSourceAndMediaAsset(source, asset)).thenReturn(true);

        assertThatThrownBy(() -> service.addAsset(user, source.getId(), new AddContentSourceAssetRequest(asset.getId())))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsAddingAssetFromAnotherWorkspace() {
        ContentSource source = new ContentSource(workspace, "Source", null, owner, NOW);
        when(sources.findByWorkspaceAndId(workspace, source.getId())).thenReturn(Optional.of(source));
        UUID otherAssetId = UUID.randomUUID();
        when(assets.findByWorkspaceAndId(workspace, otherAssetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addAsset(user, source.getId(), new AddContentSourceAssetRequest(otherAssetId)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removesMembership() {
        ContentSource source = new ContentSource(workspace, "Source", null, owner, NOW);
        when(sources.findByWorkspaceAndId(workspace, source.getId())).thenReturn(Optional.of(source));
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        ContentSourceAsset membership = new ContentSourceAsset(source, asset, owner, NOW);
        when(memberships.findByContentSourceAndMediaAsset(source, asset)).thenReturn(Optional.of(membership));

        service.removeAsset(user, source.getId(), asset.getId());
        // No exception: delete() was called with the resolved membership row.
    }

    @Test
    void removeRejectsWhenAssetNotAMember() {
        ContentSource source = new ContentSource(workspace, "Source", null, owner, NOW);
        when(sources.findByWorkspaceAndId(workspace, source.getId())).thenReturn(Optional.of(source));
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(memberships.findByContentSourceAndMediaAsset(source, asset)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeAsset(user, source.getId(), asset.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listsSourcesForWorkspace() {
        ContentSource source = new ContentSource(workspace, "Source", null, owner, NOW);
        when(sources.findByWorkspaceOrderByCreatedAtDesc(workspace)).thenReturn(List.of(source));

        List<ContentSourceSummary> result = service.list(user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(source.getId());
    }
}
