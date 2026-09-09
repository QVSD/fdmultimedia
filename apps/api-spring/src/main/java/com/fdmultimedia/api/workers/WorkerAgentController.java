package com.fdmultimedia.api.workers;

import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/worker-agent")
public class WorkerAgentController {

    private final WorkerService workerService;

    public WorkerAgentController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @PostMapping("/register")
    public WorkerRegistrationResponse register(
            Authentication authentication,
            @Valid @RequestBody WorkerRegistrationRequest request) {
        return workerService.register((WorkerPrincipal) authentication.getPrincipal(), request);
    }

    @PostMapping("/heartbeat")
    public WorkerHeartbeatResponse heartbeat(
            Authentication authentication,
            @Valid @RequestBody WorkerHeartbeatRequest request) {
        return workerService.heartbeat((WorkerPrincipal) authentication.getPrincipal(), request);
    }
}
