package com.fdmultimedia.api.adaptivelifecycle;

import com.fdmultimedia.api.adaptivelifecycle.AdaptiveLifecycleModels.RobotAdaptiveLifecycle;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdaptiveLifecycleController {
    private final AdaptiveLifecycleService service;
    public AdaptiveLifecycleController(AdaptiveLifecycleService service) { this.service = service; }

    @GetMapping("/api/robots/{robotId}/adaptive-lifecycle")
    public RobotAdaptiveLifecycle get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID robotId) {
        return service.get(user, robotId);
    }
}
