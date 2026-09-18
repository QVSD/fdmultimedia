package com.fdmultimedia.api.publishing;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.workspaces.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicationRepository extends JpaRepository<Publication, UUID> {

    List<Publication> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace);

    List<Publication> findByWorkspaceAndAssetOrderByCreatedAtDesc(Workspace workspace, MediaAsset asset);

    List<Publication> findByWorkspaceAndContentDraftIdOrderByCreatedAtDesc(Workspace workspace, UUID contentDraftId);

    Optional<Publication> findByWorkspaceAndId(Workspace workspace, UUID id);

    Optional<Publication> findByJobId(UUID jobId);
}
