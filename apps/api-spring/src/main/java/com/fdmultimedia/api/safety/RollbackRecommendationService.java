package com.fdmultimedia.api.safety;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.RevisionSummary;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.RollbackRequest;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalService;
import com.fdmultimedia.api.robotchanges.RobotConfigurationRevision;
import com.fdmultimedia.api.robotchanges.RobotConfigurationRevisionRepository;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotRepository;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.RecommendationRecord;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.RecommendationStatus;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Human governance of {@code RollbackRecommendation}s. Acknowledge and dismiss only change the recommendation. The single
 * Robot-mutating action, {@link #rollback}, delegates to the canonical Phase 17I rollback service and is reachable only
 * from an explicit human request (never from a scheduler or listener).
 */
@Service
public class RollbackRecommendationService {
    static final int MAX_LIST = 50;

    private final SafetyStore store;
    private final AuthService auth;
    private final RobotConfigurationRevisionRepository revisions;
    private final RobotRepository robots;
    private final RobotChangeProposalService canonicalRollback;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RollbackRecommendationService(SafetyStore store, AuthService auth, RobotConfigurationRevisionRepository revisions,
            RobotRepository robots, RobotChangeProposalService canonicalRollback, TransactionTemplate tx, Clock clock) {
        this.store = store; this.auth = auth; this.revisions = revisions; this.robots = robots;
        this.canonicalRollback = canonicalRollback; this.tx = tx; this.clock = clock;
    }

    /** A recommendation is actionable only while its revision is still the Robot's current configuration epoch. */
    static RecommendationRecord applyLifecycle(RecommendationRecord r, SafetyStore store, RobotConfigurationRevisionRepository revisions,
            RobotRepository robots, Clock clock) {
        if (!r.status().actionable()) return r;
        Instant now = Instant.now(clock);
        Optional<RobotConfigurationRevision> rollback = revisions.findByRobotIdAndRollbackOfRevisionId(r.robotId(), r.revisionId());
        if (rollback.isPresent()) {
            store.updateRecommendation(r.id(), RecommendationStatus.ROLLED_BACK, now, null, rollback.get().getId());
            return store.getRecommendation(r.workspaceId(), r.id()).orElse(r);
        }
        boolean laterRevision = revisions.maxRevision(r.robotId()) > r.robotRevision();
        Robot robot = robots.findById(r.robotId()).orElse(null);
        UUID persona = robot == null || robot.getPersona() == null ? null : robot.getPersona().getId();
        if (laterRevision || !Objects.equals(persona, r.currentPersonaId())) {
            store.updateRecommendation(r.id(), RecommendationStatus.SUPERSEDED, now, null, null);
            return store.getRecommendation(r.workspaceId(), r.id()).orElse(r);
        }
        return r;
    }

    // ---- reads (refresh actionable rows so a stale recommendation is never offered) ----

    public List<RecommendationRecord> list(AuthenticatedUser user, String status, UUID robotId, int limit) {
        Workspace workspace = auth.currentMembershipFor(user).getWorkspace();
        EnumSet<RecommendationStatus> statuses = EnumSet.noneOf(RecommendationStatus.class);
        if (status != null && !status.isBlank()) {
            try { statuses.add(RecommendationStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT))); }
            catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_STATUS"); }
        }
        int bounded = Math.max(1, Math.min(limit, MAX_LIST));
        return tx.execute(s -> {
            List<RecommendationRecord> rows = store.listRecommendations(workspace.getId(), statuses, robotId, bounded);
            return rows.stream().map(r -> applyLifecycle(r, store, revisions, robots, clock))
                    .filter(r -> statuses.isEmpty() || statuses.contains(r.status())).toList();
        });
    }

    public RecommendationRecord get(AuthenticatedUser user, UUID id) {
        Workspace workspace = auth.currentMembershipFor(user).getWorkspace();
        return tx.execute(s -> {
            RecommendationRecord r = store.getRecommendation(workspace.getId(), id).orElseThrow(RollbackRecommendationService::notFound);
            return applyLifecycle(r, store, revisions, robots, clock);
        });
    }

    // ---- human actions ----

    /** Acknowledge means "a human has seen this". It never changes a Robot. */
    public RecommendationRecord acknowledge(AuthenticatedUser user, UUID id) {
        return transition(user, id, RecommendationStatus.ACKNOWLEDGED);
    }

    /** Dismiss means "a human chooses not to roll back on this evidence". It never changes a Robot. */
    public RecommendationRecord dismiss(AuthenticatedUser user, UUID id) {
        return transition(user, id, RecommendationStatus.DISMISSED);
    }

    private RecommendationRecord transition(AuthenticatedUser user, UUID id, RecommendationStatus target) {
        var membership = auth.currentMembershipFor(user);
        UUID workspaceId = membership.getWorkspace().getId();
        return tx.execute(s -> {
            RecommendationRecord r = store.lockRecommendation(workspaceId, id).orElseThrow(RollbackRecommendationService::notFound);
            RecommendationRecord current = applyLifecycle(r, store, revisions, robots, clock);
            if (current.status() == target) return current;
            if (current.status() == RecommendationStatus.SUPERSEDED || current.status() == RecommendationStatus.ROLLED_BACK) {
                return current; // the refresh just made it non-actionable; report the committed state instead of failing
            }
            if (!current.status().actionable()) throw new ResponseStatusException(HttpStatus.CONFLICT, "RECOMMENDATION_NOT_ACTIONABLE");
            if (target == RecommendationStatus.ACKNOWLEDGED && current.status() != RecommendationStatus.OPEN) return current;
            store.updateRecommendation(id, target, Instant.now(clock), membership.getUser().getId(), null);
            return store.getRecommendation(workspaceId, id).orElseThrow(RollbackRecommendationService::notFound);
        });
    }

    /**
     * Explicit human rollback through the canonical Phase 17I service. The recommendation never bypasses 17I's own checks
     * (current revision, active previous Persona, divergence); if one fails nothing changes.
     */
    public RecommendationRecord rollback(AuthenticatedUser user, UUID id, String reason) {
        UUID workspaceId = auth.currentMembershipFor(user).getWorkspace().getId();
        // Step 1 (own transaction): persist any SUPERSEDED/ROLLED_BACK transition so rejecting below cannot roll it back.
        RecommendationRecord refreshed = tx.execute(s -> {
            RecommendationRecord r = store.lockRecommendation(workspaceId, id).orElseThrow(RollbackRecommendationService::notFound);
            return applyLifecycle(r, store, revisions, robots, clock);
        });
        if (refreshed.status() == RecommendationStatus.ROLLED_BACK) return refreshed;
        if (refreshed.status() == RecommendationStatus.SUPERSEDED) throw new ResponseStatusException(HttpStatus.CONFLICT, "RECOMMENDATION_SUPERSEDED");
        if (!refreshed.status().actionable()) throw new ResponseStatusException(HttpStatus.CONFLICT, "RECOMMENDATION_NOT_ACTIONABLE");
        // Step 2: canonical rollback and recommendation resolution commit atomically.
        return tx.execute(s -> {
            RecommendationRecord r = store.lockRecommendation(workspaceId, id).orElseThrow(RollbackRecommendationService::notFound);
            if (!r.status().actionable()) throw new ResponseStatusException(HttpStatus.CONFLICT, "RECOMMENDATION_NOT_ACTIONABLE");
            RevisionSummary rollback = canonicalRollback.rollback(user, r.robotId(), r.revisionId(), new RollbackRequest(
                    reason == null || reason.isBlank() ? "Rollback of rollback recommendation " + id : reason));
            store.updateRecommendation(id, RecommendationStatus.ROLLED_BACK, Instant.now(clock), user.id(), rollback.id());
            return store.getRecommendation(workspaceId, id).orElseThrow(RollbackRecommendationService::notFound);
        });
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Rollback recommendation not found"); }
}
