package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RobotChangeProposalRepository extends JpaRepository<RobotChangeProposal, UUID> {
    Optional<RobotChangeProposal> findByWorkspaceAndId(Workspace workspace, UUID id);

    List<RobotChangeProposal> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace, Pageable pageable);

    List<RobotChangeProposal> findByWorkspaceAndTargetRobotIdOrderByCreatedAtDesc(Workspace workspace, UUID targetRobotId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RobotChangeProposal p where p.workspace=:workspace and p.id=:id")
    Optional<RobotChangeProposal> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace, @Param("id") UUID id);
}
