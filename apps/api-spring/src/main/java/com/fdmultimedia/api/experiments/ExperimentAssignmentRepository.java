package com.fdmultimedia.api.experiments;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperimentAssignmentRepository extends JpaRepository<ExperimentAssignment, UUID> {

    Optional<ExperimentAssignment> findByRobotRunId(UUID robotRunId);

    /** Bounded, most-recent-first — never an unlimited historical scan (item 52). */
    List<ExperimentAssignment> findByExperimentOrderByAssignedAtDesc(Experiment experiment, Pageable pageable);

    long countByExperiment(Experiment experiment);

    /** Row 0: variant id, row 1: assignment count — the sole input to the balanced-selection algorithm. */
    @Query("select a.experimentVariant.id, count(a) from ExperimentAssignment a where a.experiment = :experiment group by a.experimentVariant.id")
    List<Object[]> countGroupedByVariant(@Param("experiment") Experiment experiment);
}
