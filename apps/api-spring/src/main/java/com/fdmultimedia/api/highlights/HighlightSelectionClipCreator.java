package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.assets.CreateClipRequest;
import com.fdmultimedia.api.assets.CreateClipResponse;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaAssetService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobService;
import com.fdmultimedia.api.workspaces.Workspace;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class HighlightSelectionClipCreator {
    private final HighlightSelectionItemRepository items;
    private final MediaAssetRepository assets;
    private final JobService jobs;
    private final MediaAssetService mediaAssets;

    HighlightSelectionClipCreator(HighlightSelectionItemRepository items, MediaAssetRepository assets,
            JobService jobs, MediaAssetService mediaAssets) {
        this.items = items;
        this.assets = assets;
        this.jobs = jobs;
        this.mediaAssets = mediaAssets;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(AuthenticatedUser principal, Workspace workspace, UUID itemId) {
        HighlightSelectionItem item = requireLocked(itemId, workspace);
        if (item.getClipAsset() != null) return;
        HighlightCandidate candidate = item.getCandidate();
        CreateClipResponse response = mediaAssets.createClip(principal, candidate.getAsset().getId(),
                new CreateClipRequest(candidate.getStartMs(), candidate.getEndMs() - candidate.getStartMs()));
        MediaAsset clipAsset = assets.findByWorkspaceAndId(workspace, response.asset().id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Clip asset was not created"));
        Job clipJob = jobs.getJobEntityForWorkspace(workspace, response.job().id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Clip job was not created"));
        item.attachClip(clipAsset, clipJob);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Workspace workspace, UUID itemId, String code, String message) {
        HighlightSelectionItem item = requireLocked(itemId, workspace);
        if (item.getClipAsset() == null) item.recordClipRequestFailure(code, message);
    }

    private HighlightSelectionItem requireLocked(UUID itemId, Workspace workspace) {
        return items.findByIdAndWorkspaceForUpdate(itemId, workspace)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Highlight selection item not found"));
    }
}
