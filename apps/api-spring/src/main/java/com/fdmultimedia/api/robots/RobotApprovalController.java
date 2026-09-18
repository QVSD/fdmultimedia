package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/robot-approvals")
public class RobotApprovalController {

    private final RobotApprovalService approvalService;

    public RobotApprovalController(RobotApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public List<RobotApprovalSummary> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) RobotApprovalStatus status) {
        return approvalService.list(principal, status);
    }

    @GetMapping("/{approvalId}")
    public RobotApprovalSummary get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID approvalId) {
        return approvalService.getFor(principal, approvalId);
    }

    @PostMapping("/{approvalId}/approve")
    public RobotApprovalSummary approve(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID approvalId,
            @RequestBody(required = false) ApproveRobotApprovalRequest request) {
        return approvalService.approve(principal, approvalId, request);
    }

    @PostMapping("/{approvalId}/reject")
    public RobotApprovalSummary reject(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID approvalId) {
        return approvalService.reject(principal, approvalId);
    }
}
