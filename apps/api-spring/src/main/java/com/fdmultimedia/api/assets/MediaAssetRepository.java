package com.fdmultimedia.api.assets;

import com.fdmultimedia.api.workspaces.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    List<MediaAsset> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace);

    Optional<MediaAsset> findByWorkspaceAndId(Workspace workspace, UUID id);

    Optional<MediaAsset> findByImportJobId(UUID importJobId);

    Optional<MediaAsset> findByInspectionJobId(UUID inspectionJobId);

    Optional<MediaAsset> findByProcessingJobId(UUID processingJobId);
}
