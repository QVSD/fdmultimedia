package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountRepository;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentdrafts.ContentDraftRepository;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.publishschedules.CreatePublishScheduleRequest;
import com.fdmultimedia.api.publishschedules.PublishScheduleService;
import com.fdmultimedia.api.publishschedules.PublishScheduleSummary;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RobotApprovalService {

    private final AuthService authService;
    private final RobotApprovalRepository approvals;
    private final ContentDraftRepository contentDrafts;
    private final SocialAccountRepository socialAccounts;
    private final PublishScheduleService publishScheduleService;
    private final RobotRunOutputRepository outputs;
    private final Clock clock;

    public RobotApprovalService(
            AuthService authService,
            RobotApprovalRepository approvals,
            ContentDraftRepository contentDrafts,
            SocialAccountRepository socialAccounts,
            PublishScheduleService publishScheduleService,
            RobotRunOutputRepository outputs,
            Clock clock) {
        this.authService = authService;
        this.approvals = approvals;
        this.contentDrafts = contentDrafts;
        this.socialAccounts = socialAccounts;
        this.publishScheduleService = publishScheduleService;
        this.outputs = outputs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RobotApprovalSummary> list(AuthenticatedUser principal, RobotApprovalStatus statusFilter) {
        Workspace workspace = currentWorkspace(principal);
        List<RobotApproval> rows = statusFilter != null
                ? approvals.findByWorkspaceAndStatusOrderByCreatedAtDesc(workspace, statusFilter)
                : approvals.findByWorkspaceOrderByCreatedAtDesc(workspace);
        return rows.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public RobotApprovalSummary getFor(AuthenticatedUser principal, UUID approvalId) {
        Workspace workspace = currentWorkspace(principal);
        return approvals.findByWorkspaceAndId(workspace, approvalId)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval not found"));
    }

    @Transactional
    public RobotApprovalSummary approve(AuthenticatedUser principal, UUID approvalId, ApproveRobotApprovalRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        RobotApproval approval = approvals.findByWorkspaceAndIdForUpdate(workspace, approvalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval not found"));
        if (approval.getStatus() != RobotApprovalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval is not pending");
        }
        Instant scheduledFor = (request != null && request.scheduledFor() != null)
                ? request.scheduledFor()
                : approval.getProposedScheduledFor();
        if (scheduledFor == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduledFor is required");
        }
        SocialAccount account = socialAccounts.findByWorkspaceAndId(workspace, approval.getSocialAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "SOCIAL_ACCOUNT_UNAVAILABLE"));
        ContentDraft draft = contentDrafts.findByWorkspaceAndId(workspace, approval.getContentDraftId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Draft is no longer available"));

        PublishScheduleSummary schedule = publishScheduleService.create(
                principal, draft.getId(), new CreatePublishScheduleRequest(account.getId(), scheduledFor));

        Instant now = Instant.now(clock);
        approval.approve(membership.getUser().getId(), now);
        RobotRun run = approval.getRobotRun();
        if (approval.getRobotRunOutput() == null) {
            run.setPublishScheduleId(schedule.id());
            run.markSucceeded(now);
        } else {
            approval.getRobotRunOutput().scheduled(schedule.id(), now);
        }
        return toSummary(approval);
    }

    @Transactional
    public RobotApprovalSummary reject(AuthenticatedUser principal, UUID approvalId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        RobotApproval approval = approvals.findByWorkspaceAndIdForUpdate(workspace, approvalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval not found"));
        if (approval.getStatus() != RobotApprovalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval is not pending");
        }
        Instant now = Instant.now(clock);
        approval.reject(membership.getUser().getId(), now);
        if (approval.getRobotRunOutput() == null) {
            approval.getRobotRun().markFailed("APPROVAL_REJECTED", "The proposed publication was rejected", now);
        } else {
            approval.getRobotRunOutput().failed(
                    "APPROVAL_REJECTED", "The proposed publication was rejected", now);
        }
        return toSummary(approval);
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private RobotApprovalSummary toSummary(RobotApproval approval) {
        ContentDraft draft = contentDrafts.findById(approval.getContentDraftId()).orElse(null);
        SocialAccount account = socialAccounts.findById(approval.getSocialAccountId()).orElse(null);
        RobotRun run = approval.getRobotRun();
        return new RobotApprovalSummary(
                approval.getId(),
                run.getId(),
                run.getRobot().getId(),
                run.getRobot().getName(),
                approval.getContentDraftId(),
                draft == null ? null : draft.getTitle(),
                draft == null ? null : draft.getCaption(),
                approval.getSocialAccountId(),
                account == null ? null : account.getDisplayName(),
                approval.getProposedScheduledFor(),
                approval.getStatus(),
                approval.getCreatedAt(),
                approval.getDecidedAt(),
                approval.getDecidedByUserId(),
                approval.getRobotRunOutput() == null
                        ? run.getPublishScheduleId()
                        : approval.getRobotRunOutput().getPublishScheduleId());
    }
}
