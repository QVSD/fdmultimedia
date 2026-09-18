package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
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
    private final Clock clock;

    public RobotAutomationDispatchService(
            AuthService authService, RobotRepository robots, RobotRunRepository runs, RobotProperties properties, Clock clock) {
        this.authService = authService;
        this.robots = robots;
        this.runs = runs;
        this.properties = properties;
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
        RobotRun run = runs.save(new RobotRun(workspace, robot, RobotRunTriggerType.MANUAL, robot.getSourceAsset(), now));
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
        } catch (ResponseStatusException ex) {
            log.info("Skipping scheduled run for robot {}: {}", robot.getId(), ex.getReason());
            return true;
        }
        runs.save(new RobotRun(workspace, robot, RobotRunTriggerType.SCHEDULED, robot.getSourceAsset(), now));
        return true;
    }

    private void validateCanStartRun(Workspace workspace, Robot robot, Instant now) {
        if (runs.existsByRobotAndStatusNotIn(robot, RobotRunStatus.terminalStatuses())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Robot already has an active run");
        }
        if (runs.existsByRobotAndSourceAssetAndStatus(robot, robot.getSourceAsset(), RobotRunStatus.SUCCEEDED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_ALREADY_PROCESSED");
        }
        Instant dayStart = now.truncatedTo(ChronoUnit.DAYS);
        if (runs.countByRobotAndCreatedAtGreaterThanEqual(robot, dayStart) >= robot.getMaxRunsPerDay()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DAILY_LIMIT_REACHED");
        }
        if (runs.countByWorkspaceAndStatusNotIn(workspace, RobotRunStatus.terminalStatuses()) >= properties.getMaxActiveRunsPerWorkspace()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "WORKSPACE_LIMIT_REACHED");
        }
    }
}
