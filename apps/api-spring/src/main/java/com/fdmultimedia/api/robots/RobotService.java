package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountRepository;
import com.fdmultimedia.api.accounts.SocialAccountStatus;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.assets.MediaInspectionStatus;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.publishschedules.PublishScheduleProperties;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RobotService {

    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final AuthService authService;
    private final RobotRepository robots;
    private final MediaAssetRepository assets;
    private final SocialAccountRepository socialAccounts;
    private final RobotProperties properties;
    private final PublishScheduleProperties publishScheduleProperties;
    private final RobotAutomationDispatchService dispatchService;
    private final RobotRunOrchestrator orchestrator;
    private final Clock clock;

    public RobotService(
            AuthService authService,
            RobotRepository robots,
            MediaAssetRepository assets,
            SocialAccountRepository socialAccounts,
            RobotProperties properties,
            PublishScheduleProperties publishScheduleProperties,
            RobotAutomationDispatchService dispatchService,
            RobotRunOrchestrator orchestrator,
            Clock clock) {
        this.authService = authService;
        this.robots = robots;
        this.assets = assets;
        this.socialAccounts = socialAccounts;
        this.properties = properties;
        this.publishScheduleProperties = publishScheduleProperties;
        this.dispatchService = dispatchService;
        this.orchestrator = orchestrator;
        this.clock = clock;
    }

    @Transactional
    public RobotSummary create(AuthenticatedUser principal, CreateRobotRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        String name = validateName(request.name());
        String description = validateDescription(request.description());
        MediaAsset source = assets.findByWorkspaceAndId(workspace, request.sourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source asset not found"));
        validateSourceEligibility(source);
        SocialAccount account = resolveAndValidateAccount(workspace, request.autonomyMode(), request.targetSocialAccountId());
        Integer cadenceHours = validateCadence(request.cadenceType(), request.cadenceIntervalHours());
        Integer delayMinutes = validateScheduleDelay(request.autonomyMode(), request.scheduleDelayMinutes());
        int maxRunsPerDay = validateMaxRunsPerDay(request.maxRunsPerDay());

        Instant now = Instant.now(clock);
        Robot robot = new Robot(
                workspace, name, description, request.autonomyMode(), source, account,
                request.cadenceType(), cadenceHours, delayMinutes, maxRunsPerDay, membership.getUser(), now);
        return toSummary(robots.save(robot));
    }

    @Transactional(readOnly = true)
    public List<RobotSummary> list(AuthenticatedUser principal) {
        Workspace workspace = currentWorkspace(principal);
        return robots.findByWorkspaceOrderByCreatedAtDesc(workspace).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public RobotSummary getFor(AuthenticatedUser principal, UUID robotId) {
        Workspace workspace = currentWorkspace(principal);
        return robots.findByWorkspaceAndId(workspace, robotId)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot not found"));
    }

    @Transactional
    public RobotSummary update(AuthenticatedUser principal, UUID robotId, UpdateRobotRequest request) {
        Workspace workspace = currentWorkspace(principal);
        Robot robot = robots.findByWorkspaceAndIdForUpdate(workspace, robotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot not found"));
        String name = validateName(request.name());
        String description = validateDescription(request.description());
        SocialAccount account = resolveAndValidateAccount(workspace, request.autonomyMode(), request.targetSocialAccountId());
        Integer cadenceHours = validateCadence(request.cadenceType(), request.cadenceIntervalHours());
        Integer delayMinutes = validateScheduleDelay(request.autonomyMode(), request.scheduleDelayMinutes());
        int maxRunsPerDay = validateMaxRunsPerDay(request.maxRunsPerDay());
        Instant now = Instant.now(clock);
        robot.update(name, description, request.autonomyMode(), account, request.cadenceType(), cadenceHours, delayMinutes, maxRunsPerDay, now);
        return toSummary(robot);
    }

    @Transactional
    public RobotSummary pause(AuthenticatedUser principal, UUID robotId) {
        Workspace workspace = currentWorkspace(principal);
        Robot robot = robots.findByWorkspaceAndIdForUpdate(workspace, robotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot not found"));
        robot.pause(Instant.now(clock));
        return toSummary(robot);
    }

    @Transactional
    public RobotSummary resume(AuthenticatedUser principal, UUID robotId) {
        Workspace workspace = currentWorkspace(principal);
        Robot robot = robots.findByWorkspaceAndIdForUpdate(workspace, robotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot not found"));
        robot.resume(Instant.now(clock));
        return toSummary(robot);
    }

    /** Manual and scheduled runs share the exact same start path ({@link RobotAutomationDispatchService}) — no separate "manual robot engine." */
    public RobotRunSummary runNow(AuthenticatedUser principal, UUID robotId) {
        UUID runId = dispatchService.createManualRun(principal, robotId);
        return orchestrator.getFor(principal, runId);
    }

    private void validateSourceEligibility(MediaAsset asset) {
        if (asset.getStatus() != MediaAssetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset is not ready");
        }
        if (asset.getInspectionStatus() != MediaInspectionStatus.INSPECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset is not inspected");
        }
        if (!Boolean.TRUE.equals(asset.getHasVideo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset must contain video");
        }
        if (asset.getDurationMs() == null || asset.getDurationMs() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset duration is not known");
        }
    }

    private SocialAccount resolveAndValidateAccount(Workspace workspace, RobotAutonomyMode mode, UUID targetSocialAccountId) {
        if (mode == RobotAutonomyMode.DRAFT_ONLY) {
            return null;
        }
        if (targetSocialAccountId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetSocialAccountId is required for this autonomy mode");
        }
        SocialAccount account = socialAccounts.findByWorkspaceAndId(workspace, targetSocialAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Social account not found"));
        if (account.getStatus() != SocialAccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Social account is not active");
        }
        // Mandatory real-provider safety boundary: AUTO_SCHEDULE may only ever
        // target TEST in Phase 11C. DRAFT_ONLY/REVIEW_REQUIRED may target any
        // connected account (including Instagram) because a human stays in
        // the loop before anything real is actually scheduled.
        if (mode == RobotAutonomyMode.AUTO_SCHEDULE && account.getPlatform() != SocialPlatform.TEST) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AUTONOMOUS_PROVIDER_NOT_ALLOWED");
        }
        return account;
    }

    private Integer validateCadence(RobotCadenceType cadenceType, Integer cadenceIntervalHours) {
        if (cadenceType == RobotCadenceType.MANUAL_ONLY) {
            return null;
        }
        if (cadenceIntervalHours == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cadenceIntervalHours is required for INTERVAL cadence");
        }
        if (cadenceIntervalHours < properties.getMinCadenceIntervalHours() || cadenceIntervalHours > properties.getMaxCadenceIntervalHours()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cadenceIntervalHours must be between " + properties.getMinCadenceIntervalHours()
                            + " and " + properties.getMaxCadenceIntervalHours());
        }
        return cadenceIntervalHours;
    }

    private Integer validateScheduleDelay(RobotAutonomyMode mode, Integer scheduleDelayMinutes) {
        if (mode == RobotAutonomyMode.DRAFT_ONLY) {
            return null;
        }
        if (scheduleDelayMinutes == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduleDelayMinutes is required for this autonomy mode");
        }
        if (scheduleDelayMinutes < properties.getMinScheduleDelayMinutes() || scheduleDelayMinutes > properties.getMaxScheduleDelayMinutes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scheduleDelayMinutes must be between " + properties.getMinScheduleDelayMinutes()
                            + " and " + properties.getMaxScheduleDelayMinutes());
        }
        long delaySeconds = Duration.ofMinutes(scheduleDelayMinutes).getSeconds();
        if (mode == RobotAutonomyMode.AUTO_SCHEDULE && delaySeconds < publishScheduleProperties.getMinLead().getSeconds()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scheduleDelayMinutes must be at least " + publishScheduleProperties.getMinLead().getSeconds() + " seconds");
        }
        return scheduleDelayMinutes;
    }

    private int validateMaxRunsPerDay(Integer maxRunsPerDay) {
        int value = maxRunsPerDay == null ? 1 : maxRunsPerDay;
        if (value < 1 || value > properties.getMaxRunsPerDayLimit()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxRunsPerDay must be between 1 and " + properties.getMaxRunsPerDayLimit());
        }
        return value;
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private String validateDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return trimmed;
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private RobotSummary toSummary(Robot robot) {
        SocialAccount account = robot.getTargetSocialAccount();
        return new RobotSummary(
                robot.getId(),
                robot.getName(),
                robot.getDescription(),
                robot.getStatus(),
                robot.getAutonomyMode(),
                robot.getHighlightStrategy(),
                robot.getSourceAsset().getId(),
                robot.getSourceAsset().getOriginalFilename(),
                account == null ? null : account.getId(),
                account == null ? null : account.getDisplayName(),
                robot.getCadenceType(),
                robot.getCadenceIntervalHours(),
                robot.getScheduleDelayMinutes(),
                robot.getMaxRunsPerDay(),
                robot.getNextRunAt(),
                robot.getLastRunAt(),
                robot.getCreatedAt(),
                robot.getUpdatedAt());
    }
}
