package com.fdmultimedia.api.campaigns;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentsuggestions.ContentAiProperties;
import com.fdmultimedia.api.highlights.HighlightCandidate;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobCreateRequest;
import com.fdmultimedia.api.jobs.JobService;
import com.fdmultimedia.api.jobs.JobStatus;
import com.fdmultimedia.api.jobs.JobSummary;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.robots.CampaignPlanningPolicy;
import com.fdmultimedia.api.robots.RobotRun;
import com.fdmultimedia.api.robots.RobotRunOutput;
import com.fdmultimedia.api.robots.RobotRunOutputRepository;
import com.fdmultimedia.api.robots.RobotRunRepository;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Advisory cross-output planning layer (Phase 17E). Never mutates
 * {@code RobotRunOutput} membership/order, never touches highlight
 * selection/ranking, never creates a Publication/schedule itself, and never
 * silently overwrites a Draft — see package-info for the full boundary.
 */
@Service
public class CampaignContentPlanService {

    private final AuthService authService;
    private final RobotRunRepository runs;
    private final RobotRunOutputRepository outputsRepository;
    private final CampaignContentPlanRepository plans;
    private final CampaignContentPlanItemRepository items;
    private final DeterministicCampaignPlanner deterministicPlanner;
    private final CampaignPlanPromptBuilder promptBuilder;
    private final CampaignRepetitionDetector repetitionDetector;
    private final CampaignPlanProperties properties;
    private final ContentAiProperties aiProperties;
    private final PersonaRepository personas;
    private final JobService jobService;
    private final Clock clock;

    public CampaignContentPlanService(
            AuthService authService, RobotRunRepository runs, RobotRunOutputRepository outputsRepository,
            CampaignContentPlanRepository plans, CampaignContentPlanItemRepository items,
            DeterministicCampaignPlanner deterministicPlanner, CampaignPlanPromptBuilder promptBuilder,
            CampaignRepetitionDetector repetitionDetector, CampaignPlanProperties properties,
            ContentAiProperties aiProperties, PersonaRepository personas, JobService jobService, Clock clock) {
        this.authService = authService;
        this.runs = runs;
        this.outputsRepository = outputsRepository;
        this.plans = plans;
        this.items = items;
        this.deterministicPlanner = deterministicPlanner;
        this.promptBuilder = promptBuilder;
        this.repetitionDetector = repetitionDetector;
        this.properties = properties;
        this.aiProperties = aiProperties;
        this.personas = personas;
        this.jobService = jobService;
        this.clock = clock;
    }

    // ---- orchestrator integration (called under the RobotRun's own row lock) ----

    /**
     * Idempotent: creates revision 1 the first time it sees this run, then
     * becomes a no-op once that revision (or a later one) is no longer
     * {@link CampaignPlanStatus#GENERATING}. Never blocks output/Draft
     * creation — {@code RobotMultiOutputOrchestrator} only consults
     * {@link #isBlockingAiGeneration} before starting per-output AI copy.
     */
    public void advance(RobotRun run, List<RobotRunOutput> outputs, AuthenticatedUser principal, Instant now) {
        if (run.getCampaignPlanningPolicySnapshot() == CampaignPlanningPolicy.NO_CAMPAIGN_PLAN) {
            return;
        }
        if (run.getCampaignPlanId() == null) {
            createRevision(run, outputs, principal, now);
        }
        // AI plans advance asynchronously via the Worker completion callback;
        // deterministic plans are always terminal the instant createRevision returns.
    }

    public boolean isBlockingAiGeneration(RobotRun run) {
        if (run.getCampaignPlanningPolicySnapshot() == CampaignPlanningPolicy.NO_CAMPAIGN_PLAN) {
            return false;
        }
        if (run.getCampaignPlanId() == null) {
            return true;
        }
        CampaignContentPlan plan = plans.findById(run.getCampaignPlanId()).orElse(null);
        return plan == null || plan.getStatus() == CampaignPlanStatus.GENERATING;
    }

    /** True only for a plan that ended without ever being applied — the caller should fail the whole RobotRun (item 59). */
    public Optional<String> blockedFailureCode(RobotRun run) {
        if (run.getCampaignPlanningPolicySnapshot() == CampaignPlanningPolicy.NO_CAMPAIGN_PLAN || run.getCampaignPlanId() == null) {
            return Optional.empty();
        }
        CampaignContentPlan plan = plans.findById(run.getCampaignPlanId()).orElse(null);
        if (plan == null) {
            return Optional.of("CAMPAIGN_PLAN_MISSING");
        }
        if (plan.getStatus() == CampaignPlanStatus.REJECTED) {
            return Optional.of("CAMPAIGN_PLAN_REJECTED");
        }
        if (plan.getStatus() == CampaignPlanStatus.FAILED) {
            return Optional.of("CAMPAIGN_PLAN_FAILED");
        }
        return Optional.empty();
    }

    public Optional<CampaignContentPlanItem> findItemFor(RobotRun run, RobotRunOutput output) {
        if (run.getCampaignPlanId() == null) {
            return Optional.empty();
        }
        CampaignContentPlan plan = plans.findById(run.getCampaignPlanId()).orElse(null);
        if (plan == null || plan.getStatus() != CampaignPlanStatus.APPLIED) {
            return Optional.empty();
        }
        return items.findByPlanAndRobotRunOutputId(plan, output.getId());
    }

    private void createRevision(RobotRun run, List<RobotRunOutput> outputs, AuthenticatedUser principal, Instant now) {
        int nextRevision = plans.findByRobotRunOrderByRevisionDesc(run).stream().findFirst().map(p -> p.getRevision() + 1).orElse(1);
        CampaignPlanningPolicy policy = run.getCampaignPlanningPolicySnapshot();
        List<CampaignOutputEvidence> evidence = evidenceFor(outputs);
        Persona persona = run.getPersonaIdSnapshot() == null ? null : personas.findById(run.getPersonaIdSnapshot()).orElse(null);
        Map<String, Object> configSnapshot = configSnapshot(policy);
        String fingerprint = fingerprint(run, outputs, policy, persona, configSnapshot);
        AppUser createdBy = run.getRobot().getCreatedByUser();

        String plannerVersion = policy == CampaignPlanningPolicy.DETERMINISTIC_PLAN
                ? CampaignPlanProperties.DETERMINISTIC_VERSION : CampaignPlanProperties.AI_PROMPT_VERSION;
        CampaignContentPlan plan = plans.save(new CampaignContentPlan(run, nextRevision, policy, plannerVersion,
                fingerprint, configSnapshot, createdBy, now));
        run.bindCampaignPlan(plan.getId());

        if (policy == CampaignPlanningPolicy.DETERMINISTIC_PLAN) {
            DeterministicCampaignPlanner.Plan generated = deterministicPlanner.plan(outputs.size(), run.getSourceAsset());
            persistItems(plan, outputs, generated.items().stream()
                    .map(item -> new WorkerCampaignPlanItemRequest(
                            outputs.get(item.sequence() - 1).getId(), item.role().name(), item.hookGuidance(),
                            item.captionGuidance(), item.ctaGuidance(), item.avoidRepetitionGuidance()))
                    .toList());
            plan.markReadyForReview(bounded(generated.campaignTitle(), properties.getMaxCampaignTitleLength()),
                    bounded(generated.campaignAngle(), properties.getMaxCampaignAngleLength()), now);
            plan.markApplied(null, now);
            return;
        }

        // AI_PLAN_FOR_REVIEW / AI_PLAN_AND_APPLY
        if (!aiProperties.isEnabled()) {
            plan.markFailed("AI_DISABLED", "AI campaign planning is disabled", now);
            return;
        }
        String personaSection = persona == null ? null : personaPromptSection(persona);
        String prompt = promptBuilder.build(evidence, personaSection, properties);
        if (prompt.length() > properties.getMaxPromptCharacters()) {
            plan.markFailed("CAMPAIGN_CONTEXT_UNAVAILABLE", "Campaign evidence is too large for planning", now);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("robotRunId", run.getId().toString());
        payload.put("planId", plan.getId().toString());
        JobSummary jobSummary = jobService.createForWorkspace(run.getWorkspace(), new JobCreateRequest(JobType.GENERATE_CAMPAIGN_PLAN, payload));
        Job job = jobService.getJobEntityForWorkspace(run.getWorkspace(), jobSummary.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Campaign plan job was not created"));
        plan.attachGenerationJob(job, aiProperties.getProvider(), aiProperties.getModel(), CampaignPlanProperties.AI_PROMPT_VERSION, now);
        // The prompt itself is never persisted on the plan row (mirrors ContentSuggestion.promptText handling: never exposed via a
        // summary DTO or log line). It lives only in the Job payload the Worker reads via its own authorization call... actually the
        // Worker needs the *built* prompt text, which authorizeWorkerGeneration rebuilds deterministically below from the same inputs.
    }

    private Map<String, Object> configSnapshot(CampaignPlanningPolicy policy) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("policy", policy.name());
        if (policy == CampaignPlanningPolicy.DETERMINISTIC_PLAN) {
            snapshot.put("plannerVersion", CampaignPlanProperties.DETERMINISTIC_VERSION);
        } else {
            snapshot.put("promptVersion", CampaignPlanProperties.AI_PROMPT_VERSION);
            snapshot.put("provider", aiProperties.getProvider());
            snapshot.put("model", aiProperties.getModel());
        }
        snapshot.put("maxCampaignTitleLength", properties.getMaxCampaignTitleLength());
        snapshot.put("maxCampaignAngleLength", properties.getMaxCampaignAngleLength());
        snapshot.put("maxHookGuidanceLength", properties.getMaxHookGuidanceLength());
        snapshot.put("maxCaptionGuidanceLength", properties.getMaxCaptionGuidanceLength());
        snapshot.put("maxCtaGuidanceLength", properties.getMaxCtaGuidanceLength());
        snapshot.put("maxAvoidanceGuidanceLength", properties.getMaxAvoidanceGuidanceLength());
        snapshot.put("nearDuplicateGuidanceThreshold", properties.getNearDuplicateGuidanceThreshold());
        return snapshot;
    }

    private List<CampaignOutputEvidence> evidenceFor(List<RobotRunOutput> outputs) {
        List<CampaignOutputEvidence> evidence = new ArrayList<>();
        for (RobotRunOutput output : outputs) {
            HighlightCandidate candidate = output.getCandidate();
            evidence.add(new CampaignOutputEvidence(output.getId(), output.getSelectionOrder(),
                    candidate.getStartMs(), candidate.getEndMs(), candidate.getScore(), candidate.getReason(),
                    candidate.getTranscriptExcerpt()));
        }
        return evidence;
    }

    private String personaPromptSection(Persona persona) {
        StringBuilder section = new StringBuilder();
        section.append("Persona name: ").append(persona.getName()).append('\n');
        if (persona.getAudience() != null && !persona.getAudience().isBlank()) {
            section.append("Audience: ").append(persona.getAudience()).append('\n');
        }
        if (persona.getVoiceDescription() != null && !persona.getVoiceDescription().isBlank()) {
            section.append("Voice: ").append(persona.getVoiceDescription()).append('\n');
        }
        return section.toString();
    }

    // ---- human-facing API ----

    @Transactional(readOnly = true)
    public List<CampaignContentPlanSummary> listForRun(AuthenticatedUser principal, UUID robotRunId) {
        Workspace workspace = currentWorkspace(principal);
        RobotRun run = runs.findByWorkspaceAndId(workspace, robotRunId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot run not found"));
        return plans.findByRobotRunOrderByRevisionDesc(run).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public CampaignContentPlanSummary getFor(AuthenticatedUser principal, UUID planId) {
        Workspace workspace = currentWorkspace(principal);
        CampaignContentPlan plan = plans.findByWorkspaceAndId(workspace, planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign plan not found"));
        return toSummary(plan);
    }

    @Transactional
    public CampaignContentPlanSummary apply(AuthenticatedUser principal, UUID planId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        CampaignContentPlan plan = plans.findByWorkspaceAndId(membership.getWorkspace(), planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign plan not found"));
        if (plan.getStatus() == CampaignPlanStatus.APPLIED) {
            return toSummary(plan);
        }
        if (plan.getStatus() != CampaignPlanStatus.READY_FOR_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan is not ready to apply");
        }
        // Item 24: defensive recheck — under this architecture RobotRunOutput
        // membership/order/candidates and the Persona snapshot id are immutable
        // once frozen, so this can only fire in a narrow edge case (e.g. the
        // snapshotted Persona row was hard-deleted between generation and
        // Apply), but the safety net stays real rather than merely documented.
        RobotRun run = plan.getRobotRun();
        List<RobotRunOutput> outputs = outputsFor(run);
        Persona persona = run.getPersonaIdSnapshot() == null ? null : personas.findById(run.getPersonaIdSnapshot()).orElse(null);
        String currentFingerprint = fingerprint(run, outputs, plan.getPolicy(), persona, plan.getConfigSnapshot());
        if (!currentFingerprint.equals(plan.getInputFingerprint())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CAMPAIGN_PLAN_STALE");
        }
        plan.markApplied(membership.getUser().getId(), Instant.now(clock));
        return toSummary(plan);
    }

    @Transactional
    public CampaignContentPlanSummary reject(AuthenticatedUser principal, UUID planId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        CampaignContentPlan plan = plans.findByWorkspaceAndId(membership.getWorkspace(), planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign plan not found"));
        if (plan.getStatus() != CampaignPlanStatus.READY_FOR_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan is not ready to reject");
        }
        plan.markRejected(membership.getUser().getId(), Instant.now(clock));
        return toSummary(plan);
    }

    @Transactional
    public CampaignContentPlanSummary regenerate(AuthenticatedUser principal, UUID robotRunId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        RobotRun run = runs.findByWorkspaceAndId(membership.getWorkspace(), robotRunId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot run not found"));
        if (run.getCampaignPlanningPolicySnapshot() == CampaignPlanningPolicy.NO_CAMPAIGN_PLAN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This run has no campaign planning policy");
        }
        List<RobotRunOutput> outputs = outputsFor(run);
        if (outputs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Outputs are not ready yet");
        }
        Instant now = Instant.now(clock);
        if (run.getCampaignPlanId() != null) {
            plans.findById(run.getCampaignPlanId()).ifPresent(current -> current.supersede(now));
        }
        run.bindCampaignPlan(null);
        createRevision(run, outputs, principal, now);
        return toSummary(plans.findById(run.getCampaignPlanId()).orElseThrow());
    }

    private List<RobotRunOutput> outputsFor(RobotRun run) {
        return outputsRepository.findByRobotRunOrderBySelectionOrderAsc(run);
    }

    // ---- worker-facing API ----

    @Transactional
    public WorkerCampaignPlanAuthorizationResponse authorizeWorkerGeneration(WorkerPrincipal principal, UUID jobId, String machineIdentifier) {
        Worker worker = jobService.requireOnlineWorker(principal, machineIdentifier);
        Job job = requireGenerationJob(worker, jobId);
        CampaignContentPlan plan = plans.findByGenerationJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign plan not found"));
        RobotRun run = plan.getRobotRun();
        List<RobotRunOutput> outputs = outputsFor(run);
        List<CampaignOutputEvidence> evidence = evidenceFor(outputs);
        Persona persona = run.getPersonaIdSnapshot() == null ? null : personas.findById(run.getPersonaIdSnapshot()).orElse(null);
        String personaSection = persona == null ? null : personaPromptSection(persona);
        String prompt = promptBuilder.build(evidence, personaSection, properties);
        return new WorkerCampaignPlanAuthorizationResponse(
                plan.getId(), run.getId(), plan.getProvider(), plan.getModel(), plan.getPromptVersion(), prompt,
                outputs.stream().map(RobotRunOutput::getId).toList(),
                properties.getMaxCampaignTitleLength(), properties.getMaxCampaignAngleLength(),
                properties.getMaxHookGuidanceLength(), properties.getMaxCaptionGuidanceLength(),
                properties.getMaxCtaGuidanceLength(), properties.getMaxAvoidanceGuidanceLength());
    }

    @Transactional
    public CampaignContentPlanSummary completeWorkerGeneration(WorkerPrincipal principal, UUID jobId, WorkerCampaignPlanCompletionRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireGenerationJob(worker, jobId);
        CampaignContentPlan plan = plans.findByGenerationJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign plan not found"));
        if (!plan.getId().equals(request.planId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan does not match job");
        }
        RobotRun run = plan.getRobotRun();
        List<RobotRunOutput> outputs = outputsFor(run);
        Instant now = Instant.now(clock);
        ValidatedPlan validated;
        try {
            validated = validateAndPersist(plan, outputs, request);
        } catch (PlanRejectedException ex) {
            jobService.failOwnedJob(job, worker, "AI_OUTPUT_REJECTED", ex.getMessage(), true, now);
            plan.markFailed("AI_OUTPUT_REJECTED", ex.getMessage(), now);
            return toSummary(plan);
        }
        jobService.completeOwnedJob(job, worker, Map.of("planId", plan.getId().toString()), now);
        plan.markReadyForReview(validated.title(), validated.angle(), now);
        if (plan.getPolicy() == CampaignPlanningPolicy.AI_PLAN_AND_APPLY) {
            plan.markApplied(null, now);
        }
        return toSummary(plan);
    }

    @Transactional
    public CampaignContentPlanSummary failWorkerGeneration(WorkerPrincipal principal, UUID jobId, WorkerCampaignPlanFailureRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireGenerationJob(worker, jobId);
        CampaignContentPlan plan = plans.findByGenerationJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign plan not found"));
        Instant now = Instant.now(clock);
        String message = safeErrorMessage(request.errorMessage());
        boolean terminal = Boolean.TRUE.equals(request.terminal());
        jobService.failOwnedJob(job, worker, request.errorCode(), message, terminal, now);
        if (terminal || job.getStatus() == JobStatus.FAILED) {
            plan.markFailed(request.errorCode(), message, now);
        }
        return toSummary(plan);
    }

    private static final class PlanRejectedException extends RuntimeException {
        PlanRejectedException(String message) {
            super(message);
        }
    }

    private record ValidatedPlan(String title, String angle) {
    }

    /**
     * Authoritative validation (item 11/33/54): every expected output must
     * appear exactly once, roles must be from the controlled enum, every
     * field must fit its bound, and no two accepted guidance strings may be
     * near-duplicates (item 33 — never trust the model's own uniqueness
     * claim). Nothing is persisted (and the plan's status is left untouched
     * for the caller to transition atomically with the correct timestamp)
     * unless the whole set validates.
     */
    private ValidatedPlan validateAndPersist(CampaignContentPlan plan, List<RobotRunOutput> outputs, WorkerCampaignPlanCompletionRequest request) {
        String title = boundedRequired(request.campaignTitle(), "campaignTitle", properties.getMaxCampaignTitleLength());
        String angle = boundedRequired(request.campaignAngle(), "campaignAngle", properties.getMaxCampaignAngleLength());
        if (request.items() == null || request.items().size() != outputs.size()) {
            throw new PlanRejectedException("Campaign plan must contain exactly one item per output");
        }
        Map<UUID, RobotRunOutput> byId = new LinkedHashMap<>();
        outputs.forEach(o -> byId.put(o.getId(), o));
        List<WorkerCampaignPlanItemRequest> ordered = new ArrayList<>(request.items());
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        List<String> hookGuidances = new ArrayList<>();
        List<WorkerCampaignPlanItemRequest> validated = new ArrayList<>();
        for (WorkerCampaignPlanItemRequest item : ordered) {
            if (item.outputId() == null || !byId.containsKey(item.outputId())) {
                throw new PlanRejectedException("Campaign plan references an unknown output");
            }
            if (!seen.add(item.outputId())) {
                throw new PlanRejectedException("Campaign plan references the same output twice");
            }
            CampaignPlanRole role = parseRole(item.role());
            String hook = boundedRequired(item.hookGuidance(), "hookGuidance", properties.getMaxHookGuidanceLength());
            String caption = boundedRequired(item.captionGuidance(), "captionGuidance", properties.getMaxCaptionGuidanceLength());
            String cta = boundedOptional(item.ctaGuidance(), properties.getMaxCtaGuidanceLength());
            String avoid = boundedOptional(item.avoidRepetitionWithPrevious(), properties.getMaxAvoidanceGuidanceLength());
            for (String priorHook : hookGuidances) {
                if (repetitionDetector.nearDuplicate(hook, priorHook, properties.getNearDuplicateGuidanceThreshold())) {
                    throw new PlanRejectedException("Campaign plan proposed near-duplicate hooks across outputs");
                }
            }
            hookGuidances.add(hook);
            validated.add(new WorkerCampaignPlanItemRequest(item.outputId(), role.name(), hook, caption, cta, avoid));
        }
        persistItems(plan, outputs, validated);
        return new ValidatedPlan(title, angle);
    }

    private CampaignPlanRole parseRole(String value) {
        if (value == null) {
            throw new PlanRejectedException("Campaign plan item is missing a role");
        }
        try {
            return CampaignPlanRole.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new PlanRejectedException("Campaign plan item has an invalid role: " + value);
        }
    }

    private String boundedRequired(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new PlanRejectedException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new PlanRejectedException(field + " exceeds the maximum length");
        }
        return trimmed;
    }

    private String boundedOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new PlanRejectedException("guidance field exceeds the maximum length");
        }
        return trimmed;
    }

    private void persistItems(CampaignContentPlan plan, List<RobotRunOutput> outputs, List<WorkerCampaignPlanItemRequest> validated) {
        Map<UUID, RobotRunOutput> byId = new LinkedHashMap<>();
        outputs.forEach(o -> byId.put(o.getId(), o));
        Instant now = Instant.now(clock);
        List<CampaignContentPlanItem> rows = new ArrayList<>();
        for (WorkerCampaignPlanItemRequest item : validated) {
            RobotRunOutput output = byId.get(item.outputId());
            rows.add(new CampaignContentPlanItem(plan, output.getId(), output.getSelectionOrder(),
                    CampaignPlanRole.valueOf(item.role()), item.hookGuidance(), item.captionGuidance(),
                    item.ctaGuidance(), item.avoidRepetitionWithPrevious(), now));
        }
        rows.sort(Comparator.comparingInt(CampaignContentPlanItem::getSequence));
        items.saveAll(rows);
    }

    private Job requireGenerationJob(Worker worker, UUID jobId) {
        Job job = jobService.requireJobForWorkerWorkspace(worker, jobId);
        if (job.getType() != JobType.GENERATE_CAMPAIGN_PLAN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not a campaign plan generation");
        }
        if (job.getStatus() != JobStatus.RUNNING && job.getStatus() != JobStatus.ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not active");
        }
        return job;
    }

    private String bounded(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private String safeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Campaign plan generation failed";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /**
     * Deterministic SHA-256 over every input that could legitimately change
     * what a re-generation would produce: run/output/candidate identity
     * (immutable once frozen, per item 3/25), Persona snapshot id, and
     * planner/provider/model/prompt version. Because RobotRunOutput
     * membership, order, and candidates are immutable and Persona is a
     * frozen run-creation snapshot (item 17), none of these can actually
     * change after a plan is created — this fingerprint (and the Apply-time
     * recheck in {@link #apply}) exist as an explicit, testable safety net
     * consistent with the rest of the codebase's fingerprint pattern, even
     * though under the current architecture a plan cannot become stale.
     */
    private String fingerprint(RobotRun run, List<RobotRunOutput> outputs, CampaignPlanningPolicy policy, Persona persona, Map<String, Object> configSnapshot) {
        List<String> parts = new ArrayList<>();
        parts.add(policy.name());
        parts.add(run.getId().toString());
        for (RobotRunOutput output : outputs) {
            parts.add(output.getId() + ":" + output.getCandidate().getId() + ":" + output.getSelectionOrder());
        }
        parts.add(persona == null ? "" : persona.getId().toString());
        parts.add(configSnapshot.toString());
        return sha256Hex(String.join("|", parts));
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private CampaignContentPlanSummary toSummary(CampaignContentPlan plan) {
        List<CampaignContentPlanItemSummary> itemSummaries = items.findByPlanOrderBySequenceAsc(plan).stream()
                .map(item -> new CampaignContentPlanItemSummary(item.getId(), item.getRobotRunOutputId(), item.getSequence(),
                        item.getRole(), item.getHookGuidance(), item.getCaptionGuidance(), item.getCtaGuidance(),
                        item.getAvoidRepetitionGuidance()))
                .toList();
        return new CampaignContentPlanSummary(
                plan.getId(), plan.getRobotRun().getId(), plan.getRevision(), plan.isCurrent(), plan.getPolicy(),
                plan.getStatus(), plan.getPlannerVersion(), plan.getProvider(), plan.getModel(), plan.getPromptVersion(),
                plan.getCampaignTitle(), plan.getCampaignAngle(), plan.getInputFingerprint(), plan.getConfigSnapshot(),
                plan.getFailureCode(), plan.getFailureMessage(), false,
                plan.getCreatedAt(), plan.getUpdatedAt(), plan.getCompletedAt(), plan.getAppliedAt(), plan.getAppliedByUserId(),
                plan.getRejectedAt(), plan.getRejectedByUserId(), itemSummaries);
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }
}
