package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.AttemptResult;
import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.AttemptTrigger;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bounded recovery/trigger path for Phase 17L: one evaluation right after a human creates an authorization, plus a
 * periodic pass over at most {@value #BATCH_LIMIT} ACTIVE authorizations (least recently evaluated first, so a
 * large backlog is served fairly). Correctness never depends on a single instance: every attempt serializes on the
 * authorization row lock inside {@link AdaptiveExecutionExecutor}.
 */
@Component
public class AdaptiveExecutionReconciler {
    public static final int BATCH_LIMIT = 100;
    private static final Logger log = LoggerFactory.getLogger(AdaptiveExecutionReconciler.class);

    private final RobotAdaptiveExecutionAuthorizationRepository authorizations;
    private final AdaptiveExecutionExecutor executor;

    public AdaptiveExecutionReconciler(RobotAdaptiveExecutionAuthorizationRepository authorizations, AdaptiveExecutionExecutor executor) {
        this.authorizations = authorizations; this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCreated(AdaptiveExecutionAuthorizationCreatedEvent event) {
        run(event.authorizationId(), AttemptTrigger.AUTHORIZATION_CREATED);
    }

    @Scheduled(fixedDelayString = "${app.adaptive-execution.reconcile-interval-ms:900000}",
            initialDelayString = "${app.adaptive-execution.reconcile-initial-delay-ms:60000}")
    public void reconcile() {
        List<UUID> ids = authorizations.findActiveIdsForReconciliation(PageRequest.of(0, BATCH_LIMIT));
        for (UUID id : ids) run(id, AttemptTrigger.RECONCILIATION);
    }

    private void run(UUID authorizationId, AttemptTrigger trigger) {
        try {
            AttemptResult result = executor.attempt(authorizationId, trigger);
            if (result != null) log.info("Adaptive execution attempt authorizationId={} trigger={} result={}", authorizationId, trigger, result);
        } catch (RuntimeException ex) {
            log.warn("Adaptive execution attempt failed authorizationId={} type={}", authorizationId, ex.getClass().getSimpleName());
        }
    }
}
