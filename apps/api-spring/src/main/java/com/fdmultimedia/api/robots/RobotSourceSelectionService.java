package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.contentsources.ContentSource;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves the single next eligible MediaAsset a CONTENT_SOURCE Robot should
 * process, or reports there is none right now. Deliberately narrow: no
 * highlight/media-processing logic lives here, and it never mixes with
 * {@link RobotRunOrchestrator}, which stays completely unaware a
 * ContentSource was ever involved (every run it sees already carries a
 * plain {@code sourceAssetId}, exactly like a Phase 11C EXISTING_ASSET run).
 */
@Service
public class RobotSourceSelectionService {

    private final RobotSourceSelectionRepository selection;
    private final MediaAssetRepository assets;

    public RobotSourceSelectionService(RobotSourceSelectionRepository selection, MediaAssetRepository assets) {
        this.selection = selection;
        this.assets = assets;
    }

    /** Never throws for "nothing eligible" — that is a normal outcome the caller turns into a NO_ELIGIBLE_SOURCE run. */
    public Optional<MediaAsset> selectNext(Robot robot) {
        ContentSource source = robot.getContentSource();
        UUID sourceId = source.getId();
        UUID workspaceId = robot.getWorkspace().getId();
        UUID robotId = robot.getId();
        Optional<UUID> assetId = robot.getSelectionPolicy() == RobotSelectionPolicy.OLDEST_UNPROCESSED
                ? selection.findOldestUnprocessedAssetId(sourceId, workspaceId, robotId)
                : selection.findNewestUnprocessedAssetId(sourceId, workspaceId, robotId);
        return assetId.flatMap(assets::findById);
    }
}
