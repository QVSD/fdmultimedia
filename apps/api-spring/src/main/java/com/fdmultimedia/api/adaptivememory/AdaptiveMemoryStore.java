package com.fdmultimedia.api.adaptivememory;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Fact;
import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Memory;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for Phase 17N. Reads existing history; writes only the memory projection tables. */
public interface AdaptiveMemoryStore {
    /** Transaction-scoped advisory lock serializing every projector of one Robot. */
    void lockRobot(UUID robotId);

    /** Robot's workspace (the projection is workspace-consistent), if the Robot exists. */
    Optional<UUID> robotWorkspace(UUID robotId);

    /** Every existing immutable source fact that concerns the Robot's PERSONA transitions (bounded). */
    List<Fact> collectFacts(UUID robotId);

    /** Inserts facts not yet projected (unique source type + id + event type); returns the number newly inserted. */
    int insertMissingEvents(Collection<Fact> facts, Instant now);

    List<Fact> loadEvents(UUID robotId);

    Map<AdaptiveMemoryProjector.Key, Memory> loadMemory(UUID robotId);

    /** Inserts or updates a projection row only when its content differs; returns whether anything changed. */
    boolean upsertMemory(Memory memory, Instant now);

    void touchState(UUID robotId, UUID workspaceId, int eventCount, Instant now);

    /** Robots with adaptive history whose projection was never reconciled or is older than {@code staleBefore}. */
    List<UUID> robotsNeedingReconciliation(Instant staleBefore, int limit);

    Map<UUID, String> personaNames(Collection<UUID> personaIds);

    /** Robot that produced a Campaign Performance review (OptimizationProposal scope), within the workspace. */
    Optional<UUID> robotForReview(UUID workspaceId, UUID reviewId);

    /** The Robot's current Persona id, or null. */
    Optional<UUID> currentPersona(UUID workspaceId, UUID robotId);
}
