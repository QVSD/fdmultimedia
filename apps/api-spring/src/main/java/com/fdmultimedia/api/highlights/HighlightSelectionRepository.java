package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.workspaces.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HighlightSelectionRepository extends JpaRepository<HighlightSelection, UUID> {
    Optional<HighlightSelection> findByWorkspaceAndId(Workspace workspace, UUID id);
    List<HighlightSelection> findByWorkspaceAndAnalysisOrderByCreatedAtDesc(
            Workspace workspace, HighlightAnalysis analysis, Pageable pageable);
}
