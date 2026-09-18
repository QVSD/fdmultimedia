package com.fdmultimedia.api.contentdrafts;

import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentDraftRepository extends JpaRepository<ContentDraft, UUID> {

    List<ContentDraft> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace);

    Optional<ContentDraft> findByWorkspaceAndId(Workspace workspace, UUID id);

    /**
     * Serializes reconciliation per draft row so concurrent polls/refreshes
     * cannot both observe the same pending stage and each queue their own
     * derivative Job. See {@code ContentDraftService.reconcile}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ContentDraft d where d.workspace = :workspace and d.id = :id")
    Optional<ContentDraft> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace, @Param("id") UUID id);
}
