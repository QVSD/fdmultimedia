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

public interface RobotAdaptiveExecutionAuthorizationRepository extends JpaRepository<RobotAdaptiveExecutionAuthorization, UUID> {
    Optional<RobotAdaptiveExecutionAuthorization> findByWorkspaceAndId(Workspace workspace, UUID id);

    List<RobotAdaptiveExecutionAuthorization> findByWorkspaceAndProposalIdOrderByCreatedAtDesc(
            Workspace workspace, UUID proposalId, Pageable pageable);

    /** Lock order contract: authorization, then proposal, then Robot. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from RobotAdaptiveExecutionAuthorization a where a.id=:id")
    Optional<RobotAdaptiveExecutionAuthorization> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from RobotAdaptiveExecutionAuthorization a where a.workspace=:workspace and a.id=:id")
    Optional<RobotAdaptiveExecutionAuthorization> findByWorkspaceAndIdForUpdate(
            @Param("workspace") Workspace workspace, @Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from RobotAdaptiveExecutionAuthorization a where a.workspace=:workspace and a.proposalId=:proposalId "
            + "and a.status=com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.AuthorizationStatus.ACTIVE")
    List<RobotAdaptiveExecutionAuthorization> findActiveForProposalForUpdate(
            @Param("workspace") Workspace workspace, @Param("proposalId") UUID proposalId);

    @Query("select a.id from RobotAdaptiveExecutionAuthorization a "
            + "where a.status=com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.AuthorizationStatus.ACTIVE "
            + "order by a.lastEvaluatedAt asc nulls first, a.createdAt asc, a.id asc")
    List<UUID> findActiveIdsForReconciliation(Pageable pageable);

    @Query("select count(a) from RobotAdaptiveExecutionAuthorization a where a.proposalId=:proposalId "
            + "and a.status=com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.AuthorizationStatus.ACTIVE")
    long countActiveForProposal(@Param("proposalId") UUID proposalId);
}
