package com.fdmultimedia.api.adaptivememory;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Fact;
import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Memory;
import java.time.Instant;
import java.util.*;

/** In-memory store mirroring V43: unique (source type, id, event type) events and an (robot, from, to) keyed projection. */
final class FakeAdaptiveMemoryStore implements AdaptiveMemoryStore {
    final List<Fact> history = new ArrayList<>();               // the existing immutable source facts
    final Map<String, Fact> events = new LinkedHashMap<>();     // projected events, unique per source fact
    final Map<AdaptiveMemoryProjector.Key, Memory> rows = new LinkedHashMap<>();
    final Map<UUID, UUID> robotWorkspaces = new HashMap<>();
    final Map<UUID, UUID> currentPersonas = new HashMap<>();
    final Map<UUID, UUID> reviewRobots = new HashMap<>();
    final Map<UUID, String> names = new HashMap<>();
    int lockCalls;
    int upsertWrites;
    int stateTouches;

    private static String key(Fact f) { return f.sourceType() + "|" + f.sourceId() + "|" + f.type(); }

    @Override public void lockRobot(UUID robotId) { lockCalls++; }
    @Override public Optional<UUID> robotWorkspace(UUID robotId) { return Optional.ofNullable(robotWorkspaces.get(robotId)); }
    @Override public List<Fact> collectFacts(UUID robotId) { return history.stream().filter(f -> f.robotId().equals(robotId)).toList(); }

    @Override public int insertMissingEvents(Collection<Fact> facts, Instant now) {
        int inserted = 0;
        for (Fact f : facts) if (events.putIfAbsent(key(f), f) == null) inserted++;
        return inserted;
    }

    @Override public List<Fact> loadEvents(UUID robotId) {
        return events.values().stream().filter(f -> f.robotId().equals(robotId)).sorted(AdaptiveMemoryProjector.ORDER).toList();
    }

    @Override public Map<AdaptiveMemoryProjector.Key, Memory> loadMemory(UUID robotId) {
        Map<AdaptiveMemoryProjector.Key, Memory> result = new LinkedHashMap<>();
        rows.forEach((k, v) -> { if (k.robotId().equals(robotId)) result.put(k, v); });
        return result;
    }

    @Override public boolean upsertMemory(Memory m, Instant now) {
        AdaptiveMemoryProjector.Key k = new AdaptiveMemoryProjector.Key(m.robotId(), m.fromPersonaId(), m.toPersonaId());
        if (m.equals(rows.get(k))) return false;
        rows.put(k, m);
        upsertWrites++;
        return true;
    }

    @Override public void touchState(UUID robotId, UUID workspaceId, int eventCount, Instant now) { stateTouches++; }
    @Override public List<UUID> robotsNeedingReconciliation(Instant staleBefore, int limit) { return List.of(); }
    @Override public Map<UUID, String> personaNames(Collection<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        ids.forEach(i -> { if (names.containsKey(i)) out.put(i, names.get(i)); });
        return out;
    }
    @Override public Optional<UUID> robotForReview(UUID workspaceId, UUID reviewId) {
        UUID robot = reviewRobots.get(reviewId);
        return robot != null && workspaceId.equals(robotWorkspaces.get(robot)) ? Optional.of(robot) : Optional.empty();
    }
    @Override public Optional<UUID> currentPersona(UUID workspaceId, UUID robotId) { return Optional.ofNullable(currentPersonas.get(robotId)); }
}
