package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RobotApprovalRepository extends JpaRepository<RobotApproval, UUID> {

    List<RobotApproval> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace);

    List<RobotApproval> findByWorkspaceAndStatusOrderByCreatedAtDesc(Workspace workspace, RobotApprovalStatus status);

    Optional<RobotApproval> findByWorkspaceAndId(Workspace workspace, UUID id);

    Optional<RobotApproval> findByRobotRun(RobotRun robotRun);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from RobotApproval a where a.workspace = :workspace and a.id = :id")
    Optional<RobotApproval> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace, @Param("id") UUID id);
}
