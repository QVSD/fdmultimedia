package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The central, server-owned Robot automation loop. Two distinct
 * responsibilities run each poll cycle, kept as two clearly separate steps
 * (never merged with the unrelated PublishSchedule dispatcher or the
 * Worker/Job scheduler — see docs/ARCHITECTURE.md for why all three
 * "scheduling" layers stay separate):
 *
 * <ol>
 *   <li>Start new runs: claim due INTERVAL Robots via
 *   {@link RobotAutomationDispatchService#dispatchOne()} — this decides only
 *   "is this Robot due to start a run," never reserving a Worker or Job.</li>
 *   <li>Advance existing runs: reconcile a bounded batch of non-terminal
 *   {@link RobotRun}s via {@link RobotRunOrchestrator#reconcileOne(java.util.UUID)}
 *   so an unattended Robot progresses without any browser open.</li>
 * </ol>
 */
@Component
public class RobotAutomationScheduler {

    private static final Logger log = LoggerFactory.getLogger(RobotAutomationScheduler.class);

    private final RobotAutomationDispatchService dispatchService;
    private final RobotRunOrchestrator orchestrator;
    private final RobotRunRepository runs;
    private final RobotProperties properties;
    private final SchedulerOperationTracker operations;

    public RobotAutomationScheduler(
            RobotAutomationDispatchService dispatchService,
            RobotRunOrchestrator orchestrator,
            RobotRunRepository runs,
            RobotProperties properties,
            SchedulerOperationTracker operations) {
        this.dispatchService = dispatchService;
        this.orchestrator = orchestrator;
        this.runs = runs;
        this.properties = properties;
        this.operations = operations;
    }

    @Scheduled(fixedDelayString = "${app.robots.poll-interval-ms:15000}")
    public void poll() {
        operations.run(SchedulerOperationTracker.ROBOT_AUTOMATION, () -> {
            if (!properties.isAutomationEnabled()) return SchedulerOperationTracker.Outcome.NONE;
            int started = startDueRuns();
            int reconciled = reconcileActiveRuns();
            return new SchedulerOperationTracker.Outcome(started + reconciled, started + reconciled);
        });
    }

    private int startDueRuns() {
        int started = 0;
        int batchSize = Math.max(1, properties.getDispatchBatchSize());
        while (started < batchSize) {
            boolean didWork;
            try {
                didWork = dispatchService.dispatchOne();
            } catch (RuntimeException ex) {
                log.error("Robot scheduler dispatch cycle failed unexpectedly", ex);
                break;
            }
            if (!didWork) {
                break;
            }
            started++;
        }
        return started;
    }

    private int reconcileActiveRuns() {
        int limit = Math.max(1, properties.getReconciliationBatchSize());
        var ids = runs.findNonTerminalIds(limit);
        int reconciled = 0;
        for (java.util.UUID runId : ids) {
            try {
                orchestrator.reconcileOne(runId);
                reconciled++;
            } catch (RuntimeException ex) {
                log.error("Failed to reconcile robot run {}", runId, ex);
            }
        }
        return reconciled;
    }
}
