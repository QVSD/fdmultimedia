package com.fdmultimedia.api.adaptivememory;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.DecisionView;
import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.RobotMemory;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only endpoints (ROLE_USER). There is deliberately no mutation or user-editable outcome endpoint. */
@RestController
public class AdaptiveMemoryController {
    private final AdaptiveMemoryService service;

    public AdaptiveMemoryController(AdaptiveMemoryService service) { this.service = service; }

    @GetMapping("/api/robots/{robotId}/adaptive-memory")
    public RobotMemory memory(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID robotId) {
        return service.memory(user, robotId);
    }

    @GetMapping("/api/robots/{robotId}/adaptive-memory/decision")
    public DecisionView decision(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID robotId,
            @RequestParam UUID targetPersonaId) {
        return service.decision(user, robotId, targetPersonaId);
    }

    @GetMapping("/api/adaptive-memory/decision")
    public DecisionView reviewDecision(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam UUID sourceReviewId,
            @RequestParam UUID baselinePersonaId, @RequestParam UUID candidatePersonaId) {
        return service.decisionForReview(user, sourceReviewId, baselinePersonaId, candidatePersonaId);
    }
}
