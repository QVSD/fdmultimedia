package com.fdmultimedia.api.adaptivememory;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Fact;
import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Memory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent, convergent projection of one Robot's immutable history into ADAPTIVE_MEMORY_V1 events and rows. Safe to run from
 * any number of callers/instances: a per-Robot advisory lock serializes writers, events are unique per source fact, and rows are
 * recomputed from the full ordered event history, so a second run changes nothing. It reads existing history only and has no
 * dependency that could mutate a Robot, policy, authorization or rollback.
 */
@Service
public class AdaptiveMemoryProjectionService {
    private static final Logger log = LoggerFactory.getLogger(AdaptiveMemoryProjectionService.class);

    private final AdaptiveMemoryStore store;
    private final Clock clock;

    public AdaptiveMemoryProjectionService(AdaptiveMemoryStore store, Clock clock) { this.store = store; this.clock = clock; }

    /** Returns the number of changes made (new events plus changed/new projection rows); 0 means already converged. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reconcileRobot(UUID robotId) {
        UUID workspaceId = store.robotWorkspace(robotId).orElse(null);
        if (workspaceId == null) return 0;
        Instant now = Instant.now(clock);
        store.lockRobot(robotId);
        List<Fact> facts = store.collectFacts(robotId);
        int inserted = store.insertMissingEvents(facts, now);
        List<Fact> events = store.loadEvents(robotId);
        Map<AdaptiveMemoryProjector.Key, Memory> projected = AdaptiveMemoryProjector.project(events);
        int changed = 0;
        for (Memory m : projected.values()) if (store.upsertMemory(m, now)) changed++;
        store.touchState(robotId, workspaceId, events.size(), now);
        if (inserted + changed > 0) {
            log.info("Adaptive memory projected robotId={} newEvents={} changedTransitions={}", robotId, inserted, changed);
        }
        return inserted + changed;
    }
}
