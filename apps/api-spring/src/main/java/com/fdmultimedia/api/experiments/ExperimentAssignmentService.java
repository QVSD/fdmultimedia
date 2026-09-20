package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deterministic, multi-instance-safe A/B assignment (items 16-19). Assignment
 * never depends on any performance outcome (item 85: this class never reads
 * {@code PublicationAnalyticsSnapshot}, dashboard aggregates, or provider
 * metrics — grep confirms no such import exists here). Deliberately joins
 * the caller's own ambient transaction (default propagation, no
 * {@code REQUIRES_NEW}): {@link RobotAutomationDispatchService} calls
 * {@link #assign} from inside the exact same transaction that creates and
 * saves the RobotRun, so a crash before commit leaves neither the run nor
 * the assignment durable, and a successful commit leaves both durable
 * together — there is no window where one exists without the other
 * (items 79-82).
 */
@Service
public class ExperimentAssignmentService {

    private final ExperimentRepository experiments;
    private final ExperimentVariantRepository variants;
    private final ExperimentAssignmentRepository assignments;

    public ExperimentAssignmentService(
            ExperimentRepository experiments, ExperimentVariantRepository variants, ExperimentAssignmentRepository assignments) {
        this.experiments = experiments;
        this.variants = variants;
        this.assignments = assignments;
    }

    /**
     * Locks the Experiment row (serializing concurrent assignment for the
     * *same* experiment across API instances — item 19), re-validates ACTIVE
     * under that lock (defense in depth beneath {@code ExperimentService
     * #assertActiveForAssignment}'s earlier optimistic check), counts
     * existing assignments per variant, and picks the strictly
     * least-assigned one — ties always resolve to Variant A, a stable,
     * deterministic, auditable tie-break (item 17/18) that keeps any prefix
     * of assignments within a difference of at most one. The
     * {@code experiment_assignments_robot_run_unique} constraint is the
     * final backstop against a duplicate assignment for the same run.
     */
    @Transactional
    public ExperimentAssignment assign(Workspace workspace, UUID experimentId, UUID robotRunId, Instant now) {
        Experiment experiment = experiments.findByWorkspaceAndIdForUpdate(workspace, experimentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found"));
        if (experiment.getStatus() != ExperimentStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ROBOT_EXPERIMENT_NOT_ACTIVE");
        }
        List<ExperimentVariant> variantRows = variants.findByExperimentOrderByVariantKeyAsc(experiment);
        if (variantRows.size() != 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EXPERIMENT_VARIANTS_UNAVAILABLE");
        }
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : assignments.countGroupedByVariant(experiment)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        ExperimentVariant chosen = variantRows.get(0);
        long chosenCount = counts.getOrDefault(chosen.getId(), 0L);
        for (ExperimentVariant candidate : variantRows) {
            long candidateCount = counts.getOrDefault(candidate.getId(), 0L);
            if (candidateCount < chosenCount) {
                chosen = candidate;
                chosenCount = candidateCount;
            }
        }
        return assignments.save(new ExperimentAssignment(workspace, experiment, chosen, robotRunId, now));
    }
}
