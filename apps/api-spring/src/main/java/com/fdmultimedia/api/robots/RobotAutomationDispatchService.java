package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentsources.ContentSourceStatus;
import com.fdmultimedia.api.experiments.ExperimentAssignment;
import com.fdmultimedia.api.experiments.ExperimentAssignmentService;
import com.fdmultimedia.api.experiments.ExperimentService;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Starts new {@link RobotRun}s — for a manual "Run Now" and for the central
 * scheduler's due-Robot claim alike; both go through the exact same
 * validation and creation path, per the "no separate manual robot engine"
 * rule. Deliberately a separate bean from {@link RobotAutomationScheduler}
 * and {@link RobotRunOrchestrator} so each of its methods runs through
 * Spring's real {@code @Transactional} proxy — a same-class self-invocation
 * silently skips that proxy, which was an actual bug caught during Phase
 * 11B's own runtime acceptance.
 */
@Service
public class RobotAutomationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(RobotAutomationDispatchService.class);

    private final AuthService authService;
    private final RobotRepository robots;
    private final RobotRunRepository runs;
    private final RobotProperties properties;
    private final RobotSourceSelectionService selectionService;
    private final ExperimentService experiments;
    private final ExperimentAssignmentService experimentAssignmentService;
    private final Clock clock;

    public RobotAutomationDispatchService(
            AuthService authService,
            RobotRepository robots,
            RobotRunRepository runs,
            RobotProperties properties,
            RobotSourceSelectionService selectionService,
            ExperimentService experiments,
            ExperimentAssignmentService experimentAssignmentService,
            Clock clock) {
        this.authService = authService;
        this.robots = robots;
        this.runs = runs;
        this.properties = properties;
        this.selectionService = selectionService;
        this.experiments = experiments;
        this.experimentAssignmentService = experimentAssignmentService;
        this.clock = clock;
    }

    @Transactional
    public UUID createManualRun(AuthenticatedUser principal, UUID robotId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        if (!properties.isAutomationEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AUTOMATION_DISABLED");
        }
        Robot robot = robots.findByWorkspaceAndIdForUpdate(workspace, robotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot not found"));
        if (robot.getStatus() != RobotStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Robot is not active");
        }
        Instant now = Instant.now(clock);
        validateCanStartRun(workspace, robot, now);
        RobotRun run = runs.save(startRun(workspace, robot, RobotRunTriggerType.MANUAL, now));
        robot.recordManualRun(now);
        return run.getId();
    }

    /**
     * One central scheduler cycle claims and starts at most one due Robot.
     * If the Robot is due but blocked by a workload limit, {@code nextRunAt}
     * still advances — otherwise the very next poll would immediately
     * re-claim the same Robot and loop on it forever instead of moving on.
     */
    @Transactional
    public boolean dispatchOne() {
        Instant now = Instant.now(clock);
        Optional<Robot> due = robots.findNextDueForUpdate(now);
        if (due.isEmpty()) {
            return false;
        }
        Robot robot = due.get();
        Workspace workspace = robot.getWorkspace();
        robot.advanceNextRunAt(now);
        try {
            validateCanStartRun(workspace, robot, now);
            runs.save(startRun(workspace, robot, RobotRunTriggerType.SCHEDULED, now));
        } catch (ResponseStatusException ex) {
            log.info("Skipping scheduled run for robot {}: {}", robot.getId(), ex.getReason());
        }
        return true;
    }

    /**
     * Resolves this run's source asset and creates the (still unsaved)
     * RobotRun — the exact same path for a manual Run Now and a scheduled
     * claim, per the "no separate selection behavior for scheduled vs
     * manual" rule. For CONTENT_SOURCE robots, a source with nothing
     * eligible right now still produces a real, auditable RobotRun,
     * immediately terminal with failureCode {@code NO_ELIGIBLE_SOURCE} —
     * never silent success and never a fabricated Draft.
     */
    private RobotRun startRun(Workspace workspace, Robot robot, RobotRunTriggerType triggerType, Instant now) {
        RobotRun run;
        if (robot.getSourcePolicy() == RobotSourcePolicy.EXISTING_ASSET) {
            run = new RobotRun(workspace, robot, triggerType, robot.getSourceAsset(), now);
        } else {
            UUID contentSourceId = robot.getContentSource().getId();
            Optional<MediaAsset> selected = selectionService.selectNext(robot);
            if (selected.isPresent()) {
                run = new RobotRun(workspace, robot, triggerType, selected.get(), contentSourceId, robot.getSelectionPolicy(), now);
            } else {
                run = new RobotRun(workspace, robot, triggerType, null, contentSourceId, robot.getSelectionPolicy(), now);
                run.markFailed("NO_ELIGIBLE_SOURCE", "No eligible unprocessed asset was found in this content source", now);
            }
        }
        // Item 12/23/46: assignment happens here, in the same transaction as the
        // RobotRun's own save below, always before any treatment is consumed —
        // even a run that is already terminal (NO_ELIGIBLE_SOURCE) is assigned, so
        // it remains part of the experiment's audited population (item 46).
        if (robot.getExperimentId() != null) {
            ExperimentAssignment assignment = experimentAssignmentService.assign(workspace, robot.getExperimentId(), run.getId(), now);
            run.applyExperimentAssignment(assignment);
        }
        return run;
    }

    private void validateCanStartRun(Workspace workspace, Robot robot, Instant now) {
        if (runs.existsByRobotAndStatusNotIn(robot, RobotRunStatus.terminalStatuses())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Robot already has an active run");
        }
        if (robot.getSourcePolicy() == RobotSourcePolicy.EXISTING_ASSET) {
            if (runs.existsByRobotAndSourceAssetAndStatus(robot, robot.getSourceAsset(), RobotRunStatus.SUCCEEDED)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_ALREADY_PROCESSED");
            }
        } else if (robot.getContentSource().getStatus() != ContentSourceStatus.ACTIVE) {
            // A paused source never even attempts a run — distinct from a
            // real, empty-but-active source, which does still produce an
            // auditable NO_ELIGIBLE_SOURCE run (see startRun).
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CONTENT_SOURCE_UNAVAILABLE");
        }
        Instant dayStart = now.truncatedTo(ChronoUnit.DAYS);
        if (runs.countByRobotAndCreatedAtGreaterThanEqual(robot, dayStart) >= robot.getMaxRunsPerDay()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DAILY_LIMIT_REACHED");
        }
        if (runs.countByWorkspaceAndStatusNotIn(workspace, RobotRunStatus.terminalStatuses()) >= properties.getMaxActiveRunsPerWorkspace()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "WORKSPACE_LIMIT_REACHED");
        }
        if (robot.getExperimentId() != null) {
            // Optimistic (non-locking) pre-check (item 24) — re-verified authoritatively
            // under the Experiment's own row lock inside ExperimentAssignmentService.assign.
            experiments.assertActiveForAssignment(workspace, robot.getExperimentId());
        }
    }
}
