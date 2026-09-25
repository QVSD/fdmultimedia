package com.fdmultimedia.api.highlights;

import java.util.List;
import java.util.UUID;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HighlightSelectionItemRepository extends JpaRepository<HighlightSelectionItem, UUID> {
    List<HighlightSelectionItem> findBySelectionOrderBySelectionOrderAsc(HighlightSelection selection);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from HighlightSelectionItem i where i.id = :id and i.selection.workspace = :workspace")
    java.util.Optional<HighlightSelectionItem> findByIdAndWorkspaceForUpdate(
            @Param("id") UUID id, @Param("workspace") Workspace workspace);
}
