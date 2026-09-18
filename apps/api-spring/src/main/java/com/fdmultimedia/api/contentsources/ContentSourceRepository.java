package com.fdmultimedia.api.contentsources;

import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentSourceRepository extends JpaRepository<ContentSource, UUID> {

    List<ContentSource> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace);

    Optional<ContentSource> findByWorkspaceAndId(Workspace workspace, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ContentSource s where s.workspace = :workspace and s.id = :id")
    Optional<ContentSource> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace, @Param("id") UUID id);
}
