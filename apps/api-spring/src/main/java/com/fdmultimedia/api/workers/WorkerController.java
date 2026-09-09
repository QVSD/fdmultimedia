package com.fdmultimedia.api.workers;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workers")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @GetMapping
    public List<WorkerSummary> list(Authentication authentication) {
        return workerService.listFor((AuthenticatedUser) authentication.getPrincipal());
    }

    @GetMapping("/{workerId}")
    public WorkerSummary get(Authentication authentication, @PathVariable UUID workerId) {
        return workerService.getFor((AuthenticatedUser) authentication.getPrincipal(), workerId);
    }
}
