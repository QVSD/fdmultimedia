package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperimentRepository extends JpaRepository<Experiment, UUID> {

    List<Experiment> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace);

    Optional<Experiment> findByWorkspaceAndId(Workspace workspace, UUID id);

    /** Serializes activation/assignment for one Experiment — mirrors {@code RobotRepository.findByWorkspaceAndIdForUpdate}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Experiment e where e.workspace = :workspace and e.id = :id")
    Optional<Experiment> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace, @Param("id") UUID id);
}
