package com.fdmultimedia.api.safety;

import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded hourly safety pass over at most {@value #BATCH_LIMIT} monitorable forward revisions (those without a monitor
 * yet or still MONITORING). Each revision is evaluated in its own transaction under a per-revision advisory lock, so
 * multiple instances, restarts and explicit evaluations converge on identical, deduplicated evidence. The reconciler
 * can create evaluations and recommendations only; it holds no reference to any Robot-mutating service.
 */
@Component
public class PostChangeSafetyReconciler {
    public static final int BATCH_LIMIT = 100;
    private static final Logger log = LoggerFactory.getLogger(PostChangeSafetyReconciler.class);

    private final SafetyStore store;
    private final PostChangeSafetyService service;
    private final Clock clock;
    private final SchedulerOperationTracker operations;

    public PostChangeSafetyReconciler(SafetyStore store, PostChangeSafetyService service, Clock clock,
            SchedulerOperationTracker operations) {
        this.store = store; this.service = service; this.clock = clock; this.operations = operations;
    }

    @Scheduled(fixedDelayString = "${app.post-change-safety.reconcile-interval-ms:3600000}",
            initialDelayString = "${app.post-change-safety.reconcile-initial-delay-ms:120000}")
    public void reconcile() {
        operations.run(SchedulerOperationTracker.POST_CHANGE_SAFETY, this::reconcileBatch);
    }

    private SchedulerOperationTracker.Outcome reconcileBatch() {
        List<UUID> ids = store.monitorableRevisionIds(Instant.now(clock), BATCH_LIMIT);
        int reconciled = 0;
        for (UUID id : ids) {
            try {
                service.reconcileRevision(id);
                reconciled++;
            } catch (RuntimeException ex) {
                log.warn("Post-change safety evaluation failed revisionId={} type={}", id, ex.getClass().getSimpleName());
            }
        }
        if (!ids.isEmpty()) log.info("Post-change safety pass evaluated {} monitored revision(s)", ids.size());
        return new SchedulerOperationTracker.Outcome(ids.size(), reconciled);
    }
}
