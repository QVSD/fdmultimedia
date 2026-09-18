package com.fdmultimedia.api.robots;

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

    public RobotAutomationScheduler(
            RobotAutomationDispatchService dispatchService,
            RobotRunOrchestrator orchestrator,
            RobotRunRepository runs,
            RobotProperties properties) {
        this.dispatchService = dispatchService;
        this.orchestrator = orchestrator;
        this.runs = runs;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.robots.poll-interval-ms:15000}")
    public void poll() {
        if (!properties.isAutomationEnabled()) {
            return;
        }
        startDueRuns();
        reconcileActiveRuns();
    }

    private void startDueRuns() {
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
    }

    private void reconcileActiveRuns() {
        int limit = Math.max(1, properties.getReconciliationBatchSize());
        for (java.util.UUID runId : runs.findNonTerminalIds(limit)) {
            try {
                orchestrator.reconcileOne(runId);
            } catch (RuntimeException ex) {
                log.error("Failed to reconcile robot run {}", runId, ex);
            }
        }
    }
}
