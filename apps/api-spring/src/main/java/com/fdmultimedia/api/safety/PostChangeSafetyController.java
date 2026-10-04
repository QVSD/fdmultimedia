package com.fdmultimedia.api.safety;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Human-session endpoints (ROLE_USER, CSRF on POST). Workers are rejected by SecurityConfig. */
@RestController
public class PostChangeSafetyController {
    private final PostChangeSafetyService safety;
    private final RollbackRecommendationService recommendations;

    public PostChangeSafetyController(PostChangeSafetyService safety, RollbackRecommendationService recommendations) {
        this.safety = safety; this.recommendations = recommendations;
    }

    @PostMapping("/api/robots/{robotId}/configuration-revisions/{revisionId}/safety-evaluations")
    public EvaluationRecord evaluate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID robotId,
            @PathVariable UUID revisionId, @RequestBody(required = false) EvaluateRequest request) {
        return safety.evaluateExplicit(user, robotId, revisionId, request == null ? null : request.observationWindow());
    }

    @GetMapping("/api/robots/{robotId}/configuration-revisions/{revisionId}/safety-evaluations")
    public List<EvaluationRecord> evaluations(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID robotId,
            @PathVariable UUID revisionId, @RequestParam(defaultValue = "20") int limit) {
        return safety.evaluations(user, robotId, revisionId, limit);
    }

    @GetMapping("/api/robots/{robotId}/configuration-revisions/{revisionId}/safety")
    public RevisionSafety safety(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID robotId, @PathVariable UUID revisionId) {
        return safety.safety(user, robotId, revisionId);
    }

    @GetMapping("/api/robots/{robotId}/post-change-safety")
    public List<RevisionSafety> robotSafety(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID robotId) {
        return safety.robotSafety(user, robotId);
    }

    @GetMapping("/api/rollback-recommendations")
    public List<RecommendationRecord> list(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID robotId, @RequestParam(defaultValue = "20") int limit) {
        return recommendations.list(user, status, robotId, limit);
    }

    @GetMapping("/api/rollback-recommendations/{id}")
    public RecommendationRecord get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return recommendations.get(user, id);
    }

    @PostMapping("/api/rollback-recommendations/{id}/acknowledge")
    public RecommendationRecord acknowledge(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return recommendations.acknowledge(user, id);
    }

    @PostMapping("/api/rollback-recommendations/{id}/dismiss")
    public RecommendationRecord dismiss(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return recommendations.dismiss(user, id);
    }

    @PostMapping("/api/rollback-recommendations/{id}/rollback")
    public RecommendationRecord rollback(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
            @RequestBody(required = false) RollbackActionRequest request) {
        return recommendations.rollback(user, id, request == null ? null : request.reason());
    }
}
