package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.workspaces.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HighlightAnalysisRepository extends JpaRepository<HighlightAnalysis, UUID> {

    List<HighlightAnalysis> findByWorkspaceAndAssetOrderByCreatedAtDesc(Workspace workspace, MediaAsset asset);

    Optional<HighlightAnalysis> findFirstByWorkspaceAndAssetOrderByCreatedAtDesc(Workspace workspace, MediaAsset asset);

    Optional<HighlightAnalysis> findByWorkspaceAndId(Workspace workspace, UUID id);

    Optional<HighlightAnalysis> findByAnalysisJobId(UUID analysisJobId);
}
