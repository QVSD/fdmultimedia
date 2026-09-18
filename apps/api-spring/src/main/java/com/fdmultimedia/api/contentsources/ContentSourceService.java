package com.fdmultimedia.api.contentsources;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaDerivationType;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Human-facing management of {@link ContentSource}s and their explicit
 * {@link ContentSourceAsset} membership. Deliberately has no idea a Robot
 * exists — selection policy and per-Robot consumption live entirely in
 * {@code com.fdmultimedia.api.robots}.
 */
@Service
public class ContentSourceService {

    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final AuthService authService;
    private final ContentSourceRepository sources;
    private final ContentSourceAssetRepository memberships;
    private final MediaAssetRepository assets;
    private final Clock clock;

    public ContentSourceService(
            AuthService authService,
            ContentSourceRepository sources,
            ContentSourceAssetRepository memberships,
            MediaAssetRepository assets,
            Clock clock) {
        this.authService = authService;
        this.sources = sources;
        this.memberships = memberships;
        this.assets = assets;
        this.clock = clock;
    }

    @Transactional
    public ContentSourceSummary create(AuthenticatedUser principal, CreateContentSourceRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        String name = validateName(request.name());
        String description = validateDescription(request.description());
        Instant now = Instant.now(clock);
        ContentSource source = new ContentSource(membership.getWorkspace(), name, description, membership.getUser(), now);
        return toSummary(sources.save(source));
    }

    @Transactional(readOnly = true)
    public List<ContentSourceSummary> list(AuthenticatedUser principal) {
        Workspace workspace = currentWorkspace(principal);
        return sources.findByWorkspaceOrderByCreatedAtDesc(workspace).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public ContentSourceSummary getFor(AuthenticatedUser principal, UUID sourceId) {
        return toSummary(requireSource(currentWorkspace(principal), sourceId));
    }

    @Transactional
    public ContentSourceSummary update(AuthenticatedUser principal, UUID sourceId, UpdateContentSourceRequest request) {
        Workspace workspace = currentWorkspace(principal);
        ContentSource source = sources.findByWorkspaceAndIdForUpdate(workspace, sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content source not found"));
        source.update(validateName(request.name()), validateDescription(request.description()), Instant.now(clock));
        return toSummary(source);
    }

    @Transactional
    public ContentSourceSummary pause(AuthenticatedUser principal, UUID sourceId) {
        Workspace workspace = currentWorkspace(principal);
        ContentSource source = sources.findByWorkspaceAndIdForUpdate(workspace, sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content source not found"));
        source.pause(Instant.now(clock));
        return toSummary(source);
    }

    @Transactional
    public ContentSourceSummary resume(AuthenticatedUser principal, UUID sourceId) {
        Workspace workspace = currentWorkspace(principal);
        ContentSource source = sources.findByWorkspaceAndIdForUpdate(workspace, sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content source not found"));
        source.resume(Instant.now(clock));
        return toSummary(source);
    }

    @Transactional(readOnly = true)
    public List<ContentSourceAssetSummary> listAssets(AuthenticatedUser principal, UUID sourceId) {
        Workspace workspace = currentWorkspace(principal);
        ContentSource source = requireSource(workspace, sourceId);
        return memberships.findByContentSourceOrderByAddedAtAscIdAsc(source).stream().map(this::toAssetSummary).toList();
    }

    @Transactional
    public ContentSourceAssetSummary addAsset(AuthenticatedUser principal, UUID sourceId, AddContentSourceAssetRequest request) {
        Workspace workspace = currentWorkspace(principal);
        ContentSource source = requireSource(workspace, sourceId);
        MediaAsset asset = assets.findByWorkspaceAndId(workspace, request.mediaAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found"));
        // Only ORIGINAL assets may become Robot source input — a generated
        // CLIP/SOCIAL_VERTICAL derivative must never recursively feed back in
        // as new source content by default.
        if (asset.getDerivationType() != MediaDerivationType.ORIGINAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only original assets can be added to a content source");
        }
        if (memberships.existsByContentSourceAndMediaAsset(source, asset)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is already in this content source");
        }
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        ContentSourceAsset created = memberships.save(
                new ContentSourceAsset(source, asset, membership.getUser(), Instant.now(clock)));
        return toAssetSummary(created);
    }

    @Transactional
    public void removeAsset(AuthenticatedUser principal, UUID sourceId, UUID mediaAssetId) {
        Workspace workspace = currentWorkspace(principal);
        ContentSource source = requireSource(workspace, sourceId);
        MediaAsset asset = assets.findByWorkspaceAndId(workspace, mediaAssetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found"));
        ContentSourceAsset membership = memberships.findByContentSourceAndMediaAsset(source, asset)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset is not in this content source"));
        // Removing membership never touches the MediaAsset itself, nor any
        // RobotRun that already selected it — those rows carry their own
        // immutable snapshot and are unaffected by future membership changes.
        memberships.delete(membership);
    }

    private ContentSource requireSource(Workspace workspace, UUID sourceId) {
        return sources.findByWorkspaceAndId(workspace, sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content source not found"));
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private String validateDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return trimmed;
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private ContentSourceSummary toSummary(ContentSource source) {
        return new ContentSourceSummary(
                source.getId(),
                source.getName(),
                source.getDescription(),
                source.getType(),
                source.getStatus(),
                memberships.countByContentSource(source),
                source.getCreatedAt(),
                source.getUpdatedAt());
    }

    private ContentSourceAssetSummary toAssetSummary(ContentSourceAsset membership) {
        MediaAsset asset = membership.getMediaAsset();
        return new ContentSourceAssetSummary(
                asset.getId(),
                asset.getOriginalFilename(),
                asset.getStatus(),
                asset.getInspectionStatus(),
                asset.getDerivationType(),
                asset.getDurationMs(),
                asset.getHasVideo(),
                membership.getAddedAt());
    }
}
