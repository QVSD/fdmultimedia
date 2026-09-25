package com.fdmultimedia.api.highlights;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HighlightSelectionExclusionRepository extends JpaRepository<HighlightSelectionExclusion, UUID> {
    List<HighlightSelectionExclusion> findBySelectionOrderByCandidateRankAsc(HighlightSelection selection);
}
