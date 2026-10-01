package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class RobotChangeProposalController {
    private final RobotChangeProposalService service;

    public RobotChangeProposalController(RobotChangeProposalService service) {
        this.service = service;
    }

    @GetMapping("/api/robot-change-proposals")
    public List<Summary> list(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "50") int limit) {
        return service.list(user, limit);
    }

    @GetMapping("/api/robot-change-proposals/{id}")
    public Summary get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.get(user, id);
    }

    @GetMapping("/api/robot-change-proposals/eligibility")
    public Eligibility eligibility(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam UUID sourceOptimizationProposalId, @RequestParam UUID targetRobotId) {
        return service.eligibility(user, sourceOptimizationProposalId, targetRobotId);
    }

    @PostMapping("/api/robot-change-proposals")
    public Summary create(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody CreateRequest request) {
        return service.create(user, request);
    }

    @PostMapping("/api/robot-change-proposals/{id}/approve")
    public Summary approve(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.approve(user, id);
    }

    @PostMapping("/api/robot-change-proposals/{id}/reject")
    public Summary reject(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.reject(user, id);
    }

    @PostMapping("/api/robot-change-proposals/{id}/apply")
    public Summary apply(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.apply(user, id);
    }

    @GetMapping("/api/robots/{robotId}/configuration-revisions")
    public List<RevisionSummary> revisions(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID robotId) {
        return service.revisionsForRobot(user, robotId);
    }

    @PostMapping("/api/robots/{robotId}/configuration-revisions/{revisionId}/rollback")
    public RevisionSummary rollback(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID robotId,
            @PathVariable UUID revisionId, @RequestBody(required = false) RollbackRequest request) {
        return service.rollback(user, robotId, revisionId, request);
    }
}
