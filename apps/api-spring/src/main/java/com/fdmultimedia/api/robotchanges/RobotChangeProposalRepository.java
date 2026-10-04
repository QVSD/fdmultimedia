package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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

    List<RobotChangeProposal> findByWorkspaceAndTargetRobotIdOrderByCreatedAtAsc(
            Workspace workspace, UUID targetRobotId, Pageable pageable);

    @Query("select p from RobotChangeProposal p where p.workspace=:workspace and p.targetRobotId=:robotId "
            + "and p.expectedRobotConfigFingerprint=:fingerprint and p.createdAt>=:epochStart "
            + "and p.status in (com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.Status.READY_FOR_REVIEW,"
            + "com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.Status.APPROVED) "
            + "order by p.createdAt desc, p.id desc")
    List<RobotChangeProposal> findCurrentForLifecycle(@Param("workspace") Workspace workspace,
            @Param("robotId") UUID robotId, @Param("fingerprint") String fingerprint,
            @Param("epochStart") Instant epochStart, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RobotChangeProposal p where p.workspace=:workspace and p.id=:id")
    Optional<RobotChangeProposal> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace, @Param("id") UUID id);

    @Query("select count(p) from RobotChangeProposal p where p.workspace=:workspace and p.targetRobotId=:robotId "
            + "and p.status in (com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.Status.READY_FOR_REVIEW,"
            + "com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.Status.APPROVED)")
    long countPendingForRobot(@Param("workspace") Workspace workspace,@Param("robotId") UUID robotId);
}
