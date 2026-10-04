package com.fdmultimedia.api.adaptivememory;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.*;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotRepository;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read side of Phase 17N: bounded memory listing, the read-only transition decision for the manual flow, and the in-memory
 * {@link Screen} used by Phase 17K candidate screening. Memory can only filter a candidate; it never ranks, scores, creates
 * or mutates anything.
 */
@Service
public class AdaptiveMemoryService {
    static final int MAX_TRANSITIONS = 100;

    private final AdaptiveMemoryStore store;
    private final AdaptiveMemoryProjectionService projection;
    private final AuthService auth;
    private final RobotRepository robots;
    private final Clock clock;

    public AdaptiveMemoryService(AdaptiveMemoryStore store, AdaptiveMemoryProjectionService projection, AuthService auth,
            RobotRepository robots, Clock clock) {
        this.store = store; this.projection = projection; this.auth = auth; this.robots = robots; this.clock = clock;
    }

    /** A Robot's memory loaded once, so screening many candidates costs no further query. */
    public static final class Screen {
        private final UUID robotId;
        private final UUID robotPersonaId;
        private final Map<AdaptiveMemoryProjector.Key, Memory> memory;
        private final Instant now;

        public Screen(UUID robotId, UUID robotPersonaId, Map<AdaptiveMemoryProjector.Key, Memory> memory, Instant now) {
            this.robotId = robotId; this.robotPersonaId = robotPersonaId; this.memory = memory; this.now = now;
        }

        public Decision decide(UUID candidatePersonaId) {
            Memory m = memory.get(new AdaptiveMemoryProjector.Key(robotId, robotPersonaId, candidatePersonaId));
            return AdaptiveMemoryProjector.decide(robotId, robotPersonaId, robotPersonaId, candidatePersonaId, m, now);
        }
    }

    /** Reconciles the Robot's projection (idempotent) and returns its memory for in-memory screening. */
    public Screen screen(UUID robotId, UUID robotPersonaId) {
        projection.reconcileRobot(robotId);
        return new Screen(robotId, robotPersonaId, store.loadMemory(robotId), Instant.now(clock));
    }

    public RobotMemory memory(AuthenticatedUser user, UUID robotId) {
        Workspace workspace = auth.currentMembershipFor(user).getWorkspace();
        Robot robot = robots.findByWorkspaceAndId(workspace, robotId).orElseThrow(AdaptiveMemoryService::notFound);
        projection.reconcileRobot(robotId);
        UUID current = robot.getPersona() == null ? null : robot.getPersona().getId();
        Instant now = Instant.now(clock);
        List<Memory> rows = new ArrayList<>(store.loadMemory(robotId).values());
        rows.sort(Comparator.comparing(Memory::lastSeenAt).reversed().thenComparing(m -> m.fromPersonaId().toString())
                .thenComparing(m -> m.toPersonaId().toString()));
        List<Memory> bounded = rows.size() > MAX_TRANSITIONS ? rows.subList(0, MAX_TRANSITIONS) : rows;
        Set<UUID> ids = new HashSet<>();
        bounded.forEach(m -> { ids.add(m.fromPersonaId()); ids.add(m.toPersonaId()); });
        Map<UUID, String> names = store.personaNames(ids);
        List<MemoryView> views = bounded.stream().map(m -> {
            Decision d = AdaptiveMemoryProjector.decide(robotId, current, m.fromPersonaId(), m.toPersonaId(), m, now);
            return new MemoryView(m.fromPersonaId(), names.get(m.fromPersonaId()), m.toPersonaId(), names.get(m.toPersonaId()),
                    m.latestOutcome(), m.proposalCount(), m.applyCount(), m.rollbackCount(), m.regressionCount(), m.firstSeenAt(),
                    m.lastSeenAt(), m.latestEvidenceAt(), m.latestSafetyStatus(), !d.eligible(), d.reasons(), d.suppressionUntil(),
                    m.latestRevisionId(), m.latestEvaluationId(), m.latestRecommendationId());
        }).toList();
        return new RobotMemory(robotId, current, AdaptiveMemoryModels.ENGINE_VERSION, views, rows.size());
    }

    /** Read-only decision for a manual flow from the Robot's current Persona. A warning only: humans keep authority. */
    public DecisionView decision(AuthenticatedUser user, UUID robotId, UUID targetPersonaId) {
        Workspace workspace = auth.currentMembershipFor(user).getWorkspace();
        Robot robot = robots.findByWorkspaceAndId(workspace, robotId).orElseThrow(AdaptiveMemoryService::notFound);
        UUID current = robot.getPersona() == null ? null : robot.getPersona().getId();
        if (current == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "ROBOT_HAS_NO_PERSONA");
        return viewOf(robotId, current, current, targetPersonaId);
    }

    /** Manual proposal flow keyed by the 17H source review: the Robot is the review's run Robot, from-Persona is the review baseline. */
    public DecisionView decisionForReview(AuthenticatedUser user, UUID sourceReviewId, UUID baselinePersonaId, UUID candidatePersonaId) {
        Workspace workspace = auth.currentMembershipFor(user).getWorkspace();
        UUID robotId = store.robotForReview(workspace.getId(), sourceReviewId).orElseThrow(AdaptiveMemoryService::notFound);
        Robot robot = robots.findByWorkspaceAndId(workspace, robotId).orElseThrow(AdaptiveMemoryService::notFound);
        UUID current = robot.getPersona() == null ? null : robot.getPersona().getId();
        return viewOf(robotId, current, baselinePersonaId, candidatePersonaId);
    }

    private DecisionView viewOf(UUID robotId, UUID currentPersonaId, UUID fromPersonaId, UUID targetPersonaId) {
        projection.reconcileRobot(robotId);
        Memory m = store.loadMemory(robotId).get(new AdaptiveMemoryProjector.Key(robotId, fromPersonaId, targetPersonaId));
        Decision d = AdaptiveMemoryProjector.decide(robotId, currentPersonaId, fromPersonaId, targetPersonaId, m, Instant.now(clock));
        Map<UUID, String> names = store.personaNames(List.of(fromPersonaId, targetPersonaId));
        return new DecisionView(d, names.get(fromPersonaId), names.get(targetPersonaId), !d.eligible());
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"); }
}
