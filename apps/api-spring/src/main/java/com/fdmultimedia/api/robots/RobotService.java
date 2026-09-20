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
import com.fdmultimedia.api.contentsources.ContentSource;
import com.fdmultimedia.api.contentsources.ContentSourceRepository;
import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import com.fdmultimedia.api.experiments.ExperimentService;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.personas.PersonaStatus;
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
    private final ContentSourceRepository contentSources;
    private final PersonaRepository personas;
    private final RobotProperties properties;
    private final PublishScheduleProperties publishScheduleProperties;
    private final RobotAutomationDispatchService dispatchService;
    private final RobotRunOrchestrator orchestrator;
    private final ExperimentService experiments;
    private final Clock clock;

    public RobotService(
            AuthService authService,
            RobotRepository robots,
            MediaAssetRepository assets,
            SocialAccountRepository socialAccounts,
            ContentSourceRepository contentSources,
            PersonaRepository personas,
            RobotProperties properties,
            PublishScheduleProperties publishScheduleProperties,
            RobotAutomationDispatchService dispatchService,
            RobotRunOrchestrator orchestrator,
            ExperimentService experiments,
            Clock clock) {
        this.authService = authService;
        this.robots = robots;
        this.assets = assets;
        this.socialAccounts = socialAccounts;
        this.contentSources = contentSources;
        this.personas = personas;
        this.properties = properties;
        this.publishScheduleProperties = publishScheduleProperties;
        this.dispatchService = dispatchService;
        this.orchestrator = orchestrator;
        this.experiments = experiments;
        this.clock = clock;
    }

    @Transactional
    public RobotSummary create(AuthenticatedUser principal, CreateRobotRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        String name = validateName(request.name());
        String description = validateDescription(request.description());
        SourceConfig sourceConfig = resolveSourceConfig(workspace, request.sourcePolicy(), request.sourceAssetId(), request.contentSourceId(), request.selectionPolicy());
        SocialAccount account = resolveAndValidateAccount(workspace, request.autonomyMode(), request.targetSocialAccountId());
        Integer cadenceHours = validateCadence(request.cadenceType(), request.cadenceIntervalHours());
        Integer delayMinutes = validateScheduleDelay(request.autonomyMode(), request.scheduleDelayMinutes());
        int maxRunsPerDay = validateMaxRunsPerDay(request.maxRunsPerDay());
        AiConfig aiConfig = resolveAiConfig(workspace, request.aiPolicy(), request.personaId(), request.aiLanguageOverride(), request.aiToneOverride());
        if (request.experimentId() != null) {
            experiments.assertRobotAttachable(workspace, request.experimentId(), aiConfig.policy() != RobotAiPolicy.NO_AI);
        }

        Instant now = Instant.now(clock);
        Robot robot = new Robot(
                workspace, name, description, request.autonomyMode(),
                sourceConfig.sourcePolicy(), sourceConfig.sourceAsset(), sourceConfig.contentSource(), sourceConfig.selectionPolicy(),
                account, request.cadenceType(), cadenceHours, delayMinutes, maxRunsPerDay,
                aiConfig.policy(), aiConfig.persona(), aiConfig.languageOverride(), aiConfig.toneOverride(),
                request.experimentId(), membership.getUser(), now);
        return toSummary(robots.save(robot));
    }

    /**
     * Validates the independent AI enrichment axis (Phase 12C): NO_AI must
     * not carry Persona/override configuration it will never use (avoids
     * misleading dead config, mirrors the DB-level
     * {@code robots_ai_config_matches_policy} constraint); an AI-enabled
     * Robot's Persona, if any, must belong to this workspace and be ACTIVE —
     * re-validated again at actual generation time in
     * {@code ContentSuggestionService}, since a Persona may be archived
     * between Robot configuration and a much later run.
     */
    private AiConfig resolveAiConfig(
            Workspace workspace, RobotAiPolicy aiPolicy, UUID personaId,
            SuggestionLanguage aiLanguageOverride, SuggestionTone aiToneOverride) {
        RobotAiPolicy policy = aiPolicy != null ? aiPolicy : RobotAiPolicy.NO_AI;
        if (policy == RobotAiPolicy.NO_AI) {
            if (personaId != null || aiLanguageOverride != null || aiToneOverride != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "personaId/aiLanguageOverride/aiToneOverride are not allowed when aiPolicy is NO_AI");
            }
            return new AiConfig(policy, null, null, null);
        }
        Persona persona = null;
        if (personaId != null) {
            persona = personas.findByWorkspaceAndId(workspace, personaId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Persona not found"));
            if (persona.getStatus() != PersonaStatus.ACTIVE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "PERSONA_ARCHIVED");
            }
        }
        return new AiConfig(policy, persona, aiLanguageOverride, aiToneOverride);
    }

    private record AiConfig(RobotAiPolicy policy, Persona persona, SuggestionLanguage languageOverride, SuggestionTone toneOverride) {
    }

    /**
     * EXISTING_ASSET requires a workspace-scoped, ready-and-inspected video
     * asset (Phase 11C, unchanged). CONTENT_SOURCE requires a
     * workspace-scoped ContentSource and a selection policy; the source
     * itself may currently be empty or contain not-yet-ready assets — that
     * is a normal runtime state (see RobotSourceSelectionService), not a
     * configuration error. sourcePolicy and its counterpart config are
     * create-only, exactly like sourceAssetId already was in Phase 11C.
     */
    private SourceConfig resolveSourceConfig(
            Workspace workspace, RobotSourcePolicy sourcePolicy, UUID sourceAssetId, UUID contentSourceId, RobotSelectionPolicy selectionPolicy) {
        if (sourcePolicy == RobotSourcePolicy.EXISTING_ASSET) {
            if (sourceAssetId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceAssetId is required for EXISTING_ASSET");
            }
            MediaAsset source = assets.findByWorkspaceAndId(workspace, sourceAssetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source asset not found"));
            validateSourceEligibility(source);
            return new SourceConfig(RobotSourcePolicy.EXISTING_ASSET, source, null, null);
        }
        if (contentSourceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentSourceId is required for CONTENT_SOURCE");
        }
        if (selectionPolicy == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "selectionPolicy is required for CONTENT_SOURCE");
        }
        ContentSource source = contentSources.findByWorkspaceAndId(workspace, contentSourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content source not found"));
        return new SourceConfig(RobotSourcePolicy.CONTENT_SOURCE, null, source, selectionPolicy);
    }

    private record SourceConfig(RobotSourcePolicy sourcePolicy, MediaAsset sourceAsset, ContentSource contentSource, RobotSelectionPolicy selectionPolicy) {
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
        AiConfig aiConfig = resolveAiConfig(workspace, request.aiPolicy(), request.personaId(), request.aiLanguageOverride(), request.aiToneOverride());
        if (request.experimentId() != null) {
            experiments.assertRobotAttachable(workspace, request.experimentId(), aiConfig.policy() != RobotAiPolicy.NO_AI);
        }
        Instant now = Instant.now(clock);
        robot.update(name, description, request.autonomyMode(), account, request.cadenceType(), cadenceHours, delayMinutes, maxRunsPerDay,
                aiConfig.policy(), aiConfig.persona(), aiConfig.languageOverride(), aiConfig.toneOverride(), request.experimentId(), now);
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
        MediaAsset sourceAsset = robot.getSourceAsset();
        ContentSource contentSource = robot.getContentSource();
        Persona persona = robot.getPersona();
        return new RobotSummary(
                robot.getId(),
                robot.getName(),
                robot.getDescription(),
                robot.getStatus(),
                robot.getAutonomyMode(),
                robot.getHighlightStrategy(),
                robot.getSourcePolicy(),
                sourceAsset == null ? null : sourceAsset.getId(),
                sourceAsset == null ? null : sourceAsset.getOriginalFilename(),
                contentSource == null ? null : contentSource.getId(),
                contentSource == null ? null : contentSource.getName(),
                robot.getSelectionPolicy(),
                account == null ? null : account.getId(),
                account == null ? null : account.getDisplayName(),
                robot.getCadenceType(),
                robot.getCadenceIntervalHours(),
                robot.getScheduleDelayMinutes(),
                robot.getMaxRunsPerDay(),
                robot.getAiPolicy(),
                persona == null ? null : persona.getId(),
                persona == null ? null : persona.getName(),
                robot.getAiLanguageOverride(),
                robot.getAiToneOverride(),
                robot.getExperimentId(),
                robot.getNextRunAt(),
                robot.getLastRunAt(),
                robot.getCreatedAt(),
                robot.getUpdatedAt());
    }
}
