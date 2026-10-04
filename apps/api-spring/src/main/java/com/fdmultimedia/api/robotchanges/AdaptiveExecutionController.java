package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdaptiveExecutionController {
    private final AdaptiveExecutionService service;

    public AdaptiveExecutionController(AdaptiveExecutionService service) { this.service = service; }

    @PostMapping("/api/robot-change-proposals/{id}/execution-authorizations")
    public AuthorizationSummary create(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
            @RequestBody(required = false) CreateRequest request) {
        return service.create(user, id, request);
    }

    @GetMapping("/api/robot-change-proposals/{id}/execution-authorizations")
    public List<AuthorizationSummary> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.list(user, id);
    }

    @GetMapping("/api/adaptive-execution-authorizations/{id}/eligibility")
    public ExecutionEligibility eligibility(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.eligibility(user, id);
    }

    @GetMapping("/api/adaptive-execution-authorizations/{id}/attempts")
    public List<AttemptSummary> attempts(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.attempts(user, id);
    }

    @PostMapping("/api/adaptive-execution-authorizations/{id}/revoke")
    public AuthorizationSummary revoke(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.revoke(user, id);
    }
}
