package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RobotController {

    private final RobotService robotService;
    private final RobotRunOrchestrator runOrchestrator;

    public RobotController(RobotService robotService, RobotRunOrchestrator runOrchestrator) {
        this.robotService = robotService;
        this.runOrchestrator = runOrchestrator;
    }

    @GetMapping("/robots")
    public List<RobotSummary> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return robotService.list(principal);
    }

    @PostMapping("/robots")
    public RobotSummary create(@AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody CreateRobotRequest request) {
        return robotService.create(principal, request);
    }

    @GetMapping("/robots/{robotId}")
    public RobotSummary get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID robotId) {
        return robotService.getFor(principal, robotId);
    }

    @PatchMapping("/robots/{robotId}")
    public RobotSummary update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID robotId,
            @Valid @RequestBody UpdateRobotRequest request) {
        return robotService.update(principal, robotId, request);
    }

    @PostMapping("/robots/{robotId}/run")
    public RobotRunSummary run(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID robotId) {
        return robotService.runNow(principal, robotId);
    }

    @PostMapping("/robots/{robotId}/pause")
    public RobotSummary pause(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID robotId) {
        return robotService.pause(principal, robotId);
    }

    @PostMapping("/robots/{robotId}/resume")
    public RobotSummary resume(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID robotId) {
        return robotService.resume(principal, robotId);
    }

    @GetMapping("/robots/{robotId}/runs")
    public List<RobotRunSummary> runsForRobot(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID robotId) {
        return runOrchestrator.listFor(principal, robotId);
    }

    @GetMapping("/robot-runs")
    public List<RobotRunSummary> allRuns(@AuthenticationPrincipal AuthenticatedUser principal) {
        return runOrchestrator.listFor(principal, null);
    }

    @GetMapping("/robot-runs/{runId}")
    public RobotRunSummary getRun(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID runId) {
        return runOrchestrator.getFor(principal, runId);
    }

    @PostMapping("/robot-runs/{runId}/cancel")
    public RobotRunSummary cancelRun(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID runId) {
        return runOrchestrator.cancel(principal, runId);
    }
}
