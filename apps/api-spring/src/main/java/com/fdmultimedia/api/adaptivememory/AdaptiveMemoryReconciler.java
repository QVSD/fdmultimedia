package com.fdmultimedia.api.adaptivememory;

import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded repair path for Phase 17N: a startup pass and an hourly pass re-project at most {@value #BATCH_LIMIT} Robots whose
 * projection is missing or older than one hour (least recently reconciled first). Each Robot is projected in its own transaction under
 * a per-Robot advisory lock, so several instances and restarts converge without duplicate events. Reads already reconcile on demand.
 */
@Component
public class AdaptiveMemoryReconciler {
    public static final int BATCH_LIMIT = 100;
    static final Duration STALE_AFTER = Duration.ofHours(1);
    private static final Logger log = LoggerFactory.getLogger(AdaptiveMemoryReconciler.class);

    private final AdaptiveMemoryStore store;
    private final AdaptiveMemoryProjectionService projection;
    private final Clock clock;
    private final SchedulerOperationTracker operations;

    public AdaptiveMemoryReconciler(AdaptiveMemoryStore store, AdaptiveMemoryProjectionService projection, Clock clock,
            SchedulerOperationTracker operations) {
        this.store = store; this.projection = projection; this.clock = clock; this.operations = operations;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startupRepair() { reconcile(); }

    @Scheduled(fixedDelayString = "${app.adaptive-memory.reconcile-interval-ms:3600000}",
            initialDelayString = "${app.adaptive-memory.reconcile-initial-delay-ms:300000}")
    public void reconcile() {
        operations.run(SchedulerOperationTracker.ADAPTIVE_MEMORY, this::reconcileBatch);
    }

    private SchedulerOperationTracker.Outcome reconcileBatch() {
        List<UUID> robots = store.robotsNeedingReconciliation(Instant.now(clock).minus(STALE_AFTER), BATCH_LIMIT);
        int changed = 0;
        for (UUID robotId : robots) {
            try {
                changed += projection.reconcileRobot(robotId);
            } catch (RuntimeException ex) {
                log.warn("Adaptive memory reconciliation failed robotId={} type={}", robotId, ex.getClass().getSimpleName());
            }
        }
        if (changed > 0) log.info("Adaptive memory pass reconciled {} Robot(s), {} change(s)", robots.size(), changed);
        return new SchedulerOperationTracker.Outcome(robots.size(), changed);
    }
}
