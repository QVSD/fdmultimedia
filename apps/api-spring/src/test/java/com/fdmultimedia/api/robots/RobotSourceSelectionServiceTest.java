package com.fdmultimedia.api.robots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.contentsources.ContentSource;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The eligibility/ordering/exclusion SQL itself lives in a native query and
 * is proven against real PostgreSQL at runtime (see Phase 11D's runtime
 * acceptance and concurrency proof — this repository has no Testcontainers
 * infrastructure, exactly like Phase 11B/11C's own claim queries). This test
 * only proves the service picks the right query per policy and correctly
 * turns a returned id into a loaded MediaAsset, or an empty selection when
 * nothing was found — the actual query logic is not exercised here.
 */
class RobotSourceSelectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final RobotSourceSelectionRepository selection = mock(RobotSourceSelectionRepository.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final RobotSourceSelectionService service = new RobotSourceSelectionService(selection, assets);

    private Workspace workspace;
    private AppUser owner;
    private ContentSource contentSource;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        contentSource = new ContentSource(workspace, "Incoming Tech Videos", null, owner, NOW);
    }

    @Test
    void oldestPolicyUsesOldestQueryAndLoadsAsset() {
        Robot robot = contentSourceRobot(RobotSelectionPolicy.OLDEST_UNPROCESSED);
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/a.mp4", NOW);
        when(selection.findOldestUnprocessedAssetId(contentSource.getId(), workspace.getId(), robot.getId()))
                .thenReturn(Optional.of(asset.getId()));
        when(assets.findById(asset.getId())).thenReturn(Optional.of(asset));

        Optional<MediaAsset> result = service.selectNext(robot);

        assertThat(result).contains(asset);
        verify(selection).findOldestUnprocessedAssetId(contentSource.getId(), workspace.getId(), robot.getId());
        verifyNoMoreInteractions(selection);
    }

    @Test
    void newestPolicyUsesNewestQuery() {
        Robot robot = contentSourceRobot(RobotSelectionPolicy.NEWEST_UNPROCESSED);
        when(selection.findNewestUnprocessedAssetId(contentSource.getId(), workspace.getId(), robot.getId()))
                .thenReturn(Optional.empty());

        Optional<MediaAsset> result = service.selectNext(robot);

        assertThat(result).isEmpty();
        verify(selection).findNewestUnprocessedAssetId(contentSource.getId(), workspace.getId(), robot.getId());
        verifyNoMoreInteractions(selection);
    }

    @Test
    void returnsEmptyWhenNothingEligible() {
        Robot robot = contentSourceRobot(RobotSelectionPolicy.OLDEST_UNPROCESSED);
        when(selection.findOldestUnprocessedAssetId(contentSource.getId(), workspace.getId(), robot.getId()))
                .thenReturn(Optional.empty());

        assertThat(service.selectNext(robot)).isEmpty();
    }

    private Robot contentSourceRobot(RobotSelectionPolicy policy) {
        SocialAccount account = null;
        return new Robot(workspace, "Robot", null, RobotAutonomyMode.DRAFT_ONLY,
                RobotSourcePolicy.CONTENT_SOURCE, null, contentSource, policy, account,
                RobotCadenceType.MANUAL_ONLY, null, null, 1, owner, NOW);
    }
}
