package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.workspaces.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HighlightCandidateRepository extends JpaRepository<HighlightCandidate, UUID> {

    List<HighlightCandidate> findByAnalysisOrderByRankAsc(HighlightAnalysis analysis);

    Optional<HighlightCandidate> findByWorkspaceAndId(Workspace workspace, UUID id);

    void deleteByAnalysis(HighlightAnalysis analysis);
}
