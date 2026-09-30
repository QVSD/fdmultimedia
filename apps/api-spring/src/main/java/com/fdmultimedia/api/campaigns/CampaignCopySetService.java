package com.fdmultimedia.api.campaigns;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentsuggestions.ContentAiProperties;
import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
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
import com.fdmultimedia.api.robots.CopyCoordinationPolicy;
import com.fdmultimedia.api.robots.RobotRun;
import com.fdmultimedia.api.robots.RobotRunOutput;
import com.fdmultimedia.api.robots.RobotRunOutputRepository;
import com.fdmultimedia.api.robots.RobotRunRepository;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Advisory cross-output copy coordination layer (Phase 17F). Never mutates
 * {@code RobotRunOutput}/{@code CampaignContentPlan} membership or order,
 * never creates a {@code ContentSuggestion} itself (that stays lazy, driven
 * by {@code RobotMultiOutputOrchestrator.beginAi()} exactly like independent
 * generation — see that class), and never touches a Draft/Schedule/
 * Publication. See package-info for the full boundary.
 */
@Service
public class CampaignCopySetService {

    private final AuthService authService;
    private final RobotRunRepository runs;
    private final RobotRunOutputRepository outputsRepository;
    private final CampaignContentPlanRepository plans;
    private final CampaignContentPlanItemRepository planItems;
    private final CampaignCopySetRepository copySets;
    private final CampaignCopyItemRepository copyItems;
    private final CoordinatedCopyPromptBuilder promptBuilder;
    private final CampaignRepetitionDetector repetitionDetector;
    private final CampaignCopyProperties properties;
    private final ContentAiProperties aiProperties;
    private final PersonaRepository personas;
    private final JobService jobService;
    private final Clock clock;

    public CampaignCopySetService(
            AuthService authService, RobotRunRepository runs, RobotRunOutputRepository outputsRepository,
            CampaignContentPlanRepository plans, CampaignContentPlanItemRepository planItems,
            CampaignCopySetRepository copySets, CampaignCopyItemRepository copyItems,
            CoordinatedCopyPromptBuilder promptBuilder, CampaignRepetitionDetector repetitionDetector,
            CampaignCopyProperties properties, ContentAiProperties aiProperties, PersonaRepository personas,
            JobService jobService, Clock clock) {
        this.authService = authService;
        this.runs = runs;
        this.outputsRepository = outputsRepository;
        this.plans = plans;
        this.planItems = planItems;
        this.copySets = copySets;
        this.copyItems = copyItems;
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
     * Idempotent: creates revision 1 only the first time it sees this run
     * AND an applied {@code CampaignContentPlan} exists for it (item 39) —
     * otherwise a no-op (the run just keeps reconciling until the plan
     * either applies or fails; a rejected/failed plan already fails the
     * RobotRun in {@code CampaignContentPlanService.blockedFailureCode}
     * *before* this method is ever reached, so "no applied plan yet" here
     * only ever means "still generating", never "permanently blocked").
     */
    public void advance(RobotRun run, List<RobotRunOutput> outputs, AuthenticatedUser principal, Instant now) {
        if (run.getCopyCoordinationPolicySnapshot() == CopyCoordinationPolicy.INDEPENDENT_COPY) {
            return;
        }
        if (run.getCampaignCopySetId() != null) {
            return;
        }
        CampaignContentPlan plan = currentAppliedPlan(run);
        if (plan == null) {
            return;
        }
        createRevision(run, plan, outputs, principal, now);
    }

    public boolean isBlockingAiGeneration(RobotRun run) {
        if (run.getCopyCoordinationPolicySnapshot() == CopyCoordinationPolicy.INDEPENDENT_COPY) {
            return false;
        }
        if (run.getCampaignCopySetId() == null) {
            return true;
        }
        CampaignCopySet copySet = copySets.findById(run.getCampaignCopySetId()).orElse(null);
        return copySet == null || copySet.getStatus() != CampaignCopySetStatus.APPLIED;
    }

    /** True only for a copy set that ended without ever being applied — the caller should fail the whole RobotRun (item 41, mirroring item 59 from campaign planning). */
    public Optional<String> blockedFailureCode(RobotRun run) {
        if (run.getCopyCoordinationPolicySnapshot() == CopyCoordinationPolicy.INDEPENDENT_COPY || run.getCampaignCopySetId() == null) {
            return Optional.empty();
        }
        CampaignCopySet copySet = copySets.findById(run.getCampaignCopySetId()).orElse(null);
        if (copySet == null) {
            return Optional.of("CAMPAIGN_COPY_GENERATION_FAILED");
        }
        if (copySet.getStatus() == CampaignCopySetStatus.REJECTED) {
            return Optional.of("CAMPAIGN_COPY_REJECTED");
        }
        if (copySet.getStatus() == CampaignCopySetStatus.FAILED) {
            return Optional.of("CAMPAIGN_COPY_GENERATION_FAILED");
        }
        return Optional.empty();
    }

    /** Only returns a value once the owning copy set is APPLIED (item 34) — {@code RobotMultiOutputOrchestrator.beginAi()} uses this instead of independent generation when present. */
    public Optional<CampaignCopyItem> findItemFor(RobotRun run, RobotRunOutput output) {
        if (run.getCampaignCopySetId() == null) {
            return Optional.empty();
        }
        CampaignCopySet copySet = copySets.findById(run.getCampaignCopySetId()).orElse(null);
        if (copySet == null || copySet.getStatus() != CampaignCopySetStatus.APPLIED) {
            return Optional.empty();
        }
        return copyItems.findByCopySetAndRobotRunOutputId(copySet, output.getId());
    }

    private CampaignContentPlan currentAppliedPlan(RobotRun run) {
        if (run.getCampaignPlanId() == null) {
            return null;
        }
        CampaignContentPlan plan = plans.findById(run.getCampaignPlanId()).orElse(null);
        return plan != null && plan.getStatus() == CampaignPlanStatus.APPLIED ? plan : null;
    }

    private void createRevision(RobotRun run, CampaignContentPlan plan, List<RobotRunOutput> outputs, AuthenticatedUser principal, Instant now) {
        int nextRevision = copySets.findByRobotRunOrderByRevisionDesc(run).stream().findFirst().map(c -> c.getRevision() + 1).orElse(1);
        Persona persona = run.getPersonaIdSnapshot() == null ? null : personas.findById(run.getPersonaIdSnapshot()).orElse(null);
        List<CampaignContentPlanItem> items = planItems.findByPlanOrderBySequenceAsc(plan);
        Map<String, Object> configSnapshot = configSnapshot();
        String fingerprint = fingerprint(run, outputs, plan, items, persona, configSnapshot);
        AppUser createdBy = run.getRobot().getCreatedByUser();

        CampaignCopySet copySet = copySets.save(new CampaignCopySet(run, plan, nextRevision, CampaignCopyProperties.PROMPT_VERSION,
                fingerprint, configSnapshot, createdBy, now));
        run.bindCampaignCopySet(copySet.getId());

        if (!aiProperties.isEnabled()) {
            copySet.markFailed("CAMPAIGN_COPY_AI_DISABLED", "Coordinated copy generation is disabled", now);
            return;
        }
        List<CoordinatedCopyOutputContext> contexts = contextsFor(outputs, items);
        Persona finalPersona = persona;
        String personaSection = finalPersona == null ? null : personaPromptSection(finalPersona);
        String prompt = promptBuilder.build(plan.getCampaignTitle(), plan.getCampaignAngle(), contexts, personaSection, properties, aiProperties);
        if (prompt.length() > properties.getMaxPromptCharacters()) {
            copySet.markFailed("CAMPAIGN_COPY_GENERATION_FAILED", "Coordinated copy evidence is too large for generation", now);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("robotRunId", run.getId().toString());
        payload.put("copySetId", copySet.getId().toString());
        JobSummary jobSummary = jobService.createForWorkspace(run.getWorkspace(), new JobCreateRequest(JobType.GENERATE_COORDINATED_SOCIAL_COPY, payload));
        Job job = jobService.getJobEntityForWorkspace(run.getWorkspace(), jobSummary.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Coordinated copy job was not created"));
        copySet.attachGenerationJob(job, aiProperties.getProvider(), aiProperties.getModel(), CampaignCopyProperties.PROMPT_VERSION, now);
    }

    private Map<String, Object> configSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("promptVersion", CampaignCopyProperties.PROMPT_VERSION);
        snapshot.put("provider", aiProperties.getProvider());
        snapshot.put("model", aiProperties.getModel());
        snapshot.put("maxSeriesTitleLength", properties.getMaxSeriesTitleLength());
        snapshot.put("maxHookLength", aiProperties.getMaxHookLength());
        snapshot.put("maxCaptionLength", aiProperties.getMaxCaptionLength());
        snapshot.put("maxHashtags", aiProperties.getMaxHashtags());
        snapshot.put("maxHashtagLength", aiProperties.getMaxHashtagLength());
        snapshot.put("maxShortTitleLength", aiProperties.getMaxShortTitleLength());
        snapshot.put("maxContinuityNoteLength", properties.getMaxContinuityNoteLength());
        snapshot.put("nearDuplicateHookThreshold", properties.getNearDuplicateHookThreshold());
        snapshot.put("duplicateCaptionOpeningThreshold", properties.getDuplicateCaptionOpeningThreshold());
        return snapshot;
    }

    private List<CoordinatedCopyOutputContext> contextsFor(List<RobotRunOutput> outputs, List<CampaignContentPlanItem> items) {
        Map<UUID, CampaignContentPlanItem> byOutput = new LinkedHashMap<>();
        items.forEach(item -> byOutput.put(item.getRobotRunOutputId(), item));
        List<CoordinatedCopyOutputContext> contexts = new ArrayList<>();
        for (RobotRunOutput output : outputs) {
            CampaignContentPlanItem item = byOutput.get(output.getId());
            if (item == null) {
                continue;
            }
            HighlightCandidate candidate = output.getCandidate();
            contexts.add(new CoordinatedCopyOutputContext(output.getId(), output.getSelectionOrder(),
                    candidate.getStartMs(), candidate.getEndMs(), candidate.getScore(), candidate.getReason(), candidate.getTranscriptExcerpt(),
                    item.getRole(), item.getHookGuidance(), item.getCaptionGuidance(), item.getCtaGuidance(), item.getAvoidRepetitionGuidance()));
        }
        return contexts;
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
    public List<CampaignCopySetSummary> listForRun(AuthenticatedUser principal, UUID robotRunId) {
        Workspace workspace = currentWorkspace(principal);
        RobotRun run = runs.findByWorkspaceAndId(workspace, robotRunId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot run not found"));
        return copySets.findByRobotRunOrderByRevisionDesc(run).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public CampaignCopySetSummary getFor(AuthenticatedUser principal, UUID copySetId) {
        Workspace workspace = currentWorkspace(principal);
        CampaignCopySet copySet = copySets.findByWorkspaceAndId(workspace, copySetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Copy set not found"));
        return toSummary(copySet);
    }

    @Transactional
    public CampaignCopySetSummary apply(AuthenticatedUser principal, UUID copySetId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        CampaignCopySet copySet = copySets.findByWorkspaceAndId(membership.getWorkspace(), copySetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Copy set not found"));
        if (copySet.getStatus() == CampaignCopySetStatus.APPLIED) {
            return toSummary(copySet);
        }
        if (copySet.getStatus() != CampaignCopySetStatus.READY_FOR_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Copy set is not ready to apply");
        }
        RobotRun run = copySet.getRobotRun();
        // Item 29/31: pinned plan revision must still be the run's current one.
        if (!copySet.getCampaignPlan().getId().equals(run.getCampaignPlanId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CAMPAIGN_COPY_STALE");
        }
        List<RobotRunOutput> outputs = outputsFor(run);
        List<CampaignContentPlanItem> items = planItems.findByPlanOrderBySequenceAsc(copySet.getCampaignPlan());
        Persona persona = run.getPersonaIdSnapshot() == null ? null : personas.findById(run.getPersonaIdSnapshot()).orElse(null);
        String currentFingerprint = fingerprint(run, outputs, copySet.getCampaignPlan(), items, persona, copySet.getConfigSnapshot());
        if (!currentFingerprint.equals(copySet.getInputFingerprint())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CAMPAIGN_COPY_STALE");
        }
        copySet.markApplied(membership.getUser().getId(), Instant.now(clock));
        return toSummary(copySet);
    }

    @Transactional
    public CampaignCopySetSummary reject(AuthenticatedUser principal, UUID copySetId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        CampaignCopySet copySet = copySets.findByWorkspaceAndId(membership.getWorkspace(), copySetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Copy set not found"));
        if (copySet.getStatus() != CampaignCopySetStatus.READY_FOR_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Copy set is not ready to reject");
        }
        copySet.markRejected(membership.getUser().getId(), Instant.now(clock));
        return toSummary(copySet);
    }

    @Transactional
    public CampaignCopySetSummary regenerate(AuthenticatedUser principal, UUID robotRunId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        RobotRun run = runs.findByWorkspaceAndId(membership.getWorkspace(), robotRunId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot run not found"));
        if (run.getCopyCoordinationPolicySnapshot() == CopyCoordinationPolicy.INDEPENDENT_COPY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This run has no copy coordination policy");
        }
        CampaignContentPlan plan = currentAppliedPlan(run);
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An applied campaign plan is required");
        }
        List<RobotRunOutput> outputs = outputsFor(run);
        if (outputs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Outputs are not ready yet");
        }
        Instant now = Instant.now(clock);
        if (run.getCampaignCopySetId() != null) {
            copySets.findById(run.getCampaignCopySetId()).ifPresent(current -> current.supersede(now));
        }
        run.bindCampaignCopySet(null);
        createRevision(run, plan, outputs, principal, now);
        return toSummary(copySets.findById(run.getCampaignCopySetId()).orElseThrow());
    }

    private List<RobotRunOutput> outputsFor(RobotRun run) {
        return outputsRepository.findByRobotRunOrderBySelectionOrderAsc(run);
    }

    // ---- worker-facing API ----

    @Transactional
    public WorkerCoordinatedCopyAuthorizationResponse authorizeWorkerGeneration(WorkerPrincipal principal, UUID jobId, String machineIdentifier) {
        Worker worker = jobService.requireOnlineWorker(principal, machineIdentifier);
        Job job = requireGenerationJob(worker, jobId);
        CampaignCopySet copySet = copySets.findByGenerationJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Copy set not found"));
        RobotRun run = copySet.getRobotRun();
        List<RobotRunOutput> outputs = outputsFor(run);
        List<CampaignContentPlanItem> items = planItems.findByPlanOrderBySequenceAsc(copySet.getCampaignPlan());
        List<CoordinatedCopyOutputContext> contexts = contextsFor(outputs, items);
        Persona persona = run.getPersonaIdSnapshot() == null ? null : personas.findById(run.getPersonaIdSnapshot()).orElse(null);
        String personaSection = persona == null ? null : personaPromptSection(persona);
        String prompt = promptBuilder.build(copySet.getCampaignPlan().getCampaignTitle(), copySet.getCampaignPlan().getCampaignAngle(),
                contexts, personaSection, properties, aiProperties);
        SuggestionLanguage language = run.getAiLanguageOverrideSnapshot() == null ? SuggestionLanguage.AUTO : run.getAiLanguageOverrideSnapshot();
        SuggestionTone tone = run.getAiToneOverrideSnapshot() == null ? SuggestionTone.NEUTRAL : run.getAiToneOverrideSnapshot();
        return new WorkerCoordinatedCopyAuthorizationResponse(
                copySet.getId(), run.getId(), copySet.getProvider(), copySet.getModel(), copySet.getPromptVersion(), prompt,
                outputs.stream().map(RobotRunOutput::getId).toList(), language.name(), tone.name(),
                properties.getMaxSeriesTitleLength(), aiProperties.getMaxHookLength(), aiProperties.getMaxCaptionLength(),
                aiProperties.getMaxHashtags(), aiProperties.getMaxHashtagLength(), aiProperties.getMaxShortTitleLength(),
                properties.getMaxContinuityNoteLength());
    }

    /**
     * Authoritative validation (item 12/13/45/46/48): every expected output
     * must appear exactly once, and no two accepted hooks/caption-openings
     * may be near-duplicates — nothing is persisted unless the whole set
     * validates (item 57 — atomic, no partial copy items).
     */
    @Transactional
    public CampaignCopySetSummary completeWorkerGeneration(WorkerPrincipal principal, UUID jobId, WorkerCoordinatedCopyCompletionRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireGenerationJob(worker, jobId);
        CampaignCopySet copySet = copySets.findByGenerationJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Copy set not found"));
        if (!copySet.getId().equals(request.copySetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Copy set does not match job");
        }
        RobotRun run = copySet.getRobotRun();
        List<RobotRunOutput> outputs = outputsFor(run);
        List<CampaignContentPlanItem> planItemsList = planItems.findByPlanOrderBySequenceAsc(copySet.getCampaignPlan());
        Instant now = Instant.now(clock);
        ValidatedCopySet validated;
        try {
            validated = validateAndPersist(copySet, outputs, planItemsList, request);
        } catch (CopySetRejectedException ex) {
            jobService.failOwnedJob(job, worker, "CAMPAIGN_COPY_INVALID_OUTPUT", ex.getMessage(), true, now);
            copySet.markFailed("CAMPAIGN_COPY_INVALID_OUTPUT", ex.getMessage(), now);
            return toSummary(copySet);
        }
        jobService.completeOwnedJob(job, worker, Map.of("copySetId", copySet.getId().toString()), now);
        copySet.markReadyForReview(validated.seriesTitle(), null, now);
        if (run.getCopyCoordinationPolicySnapshot() == CopyCoordinationPolicy.COORDINATED_COPY_AND_APPLY) {
            copySet.markApplied(null, now);
        }
        return toSummary(copySet);
    }

    @Transactional
    public CampaignCopySetSummary failWorkerGeneration(WorkerPrincipal principal, UUID jobId, WorkerCoordinatedCopyFailureRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireGenerationJob(worker, jobId);
        CampaignCopySet copySet = copySets.findByGenerationJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Copy set not found"));
        Instant now = Instant.now(clock);
        String message = safeErrorMessage(request.errorMessage());
        boolean terminal = Boolean.TRUE.equals(request.terminal());
        jobService.failOwnedJob(job, worker, request.errorCode(), message, terminal, now);
        if (terminal || job.getStatus() == JobStatus.FAILED) {
            copySet.markFailed(request.errorCode(), message, now);
        }
        return toSummary(copySet);
    }

    private record ValidatedCopySet(String seriesTitle) {
    }

    private static final class CopySetRejectedException extends RuntimeException {
        CopySetRejectedException(String message) {
            super(message);
        }
    }

    private ValidatedCopySet validateAndPersist(CampaignCopySet copySet, List<RobotRunOutput> outputs,
            List<CampaignContentPlanItem> planItemsList, WorkerCoordinatedCopyCompletionRequest request) {
        String seriesTitle = boundedRequired(request.seriesTitle(), "seriesTitle", properties.getMaxSeriesTitleLength());
        if (request.items() == null || request.items().size() != outputs.size()) {
            throw new CopySetRejectedException("Coordinated copy must contain exactly one item per output");
        }
        Map<UUID, RobotRunOutput> byId = new LinkedHashMap<>();
        outputs.forEach(o -> byId.put(o.getId(), o));
        Map<UUID, CampaignContentPlanItem> planItemByOutput = new LinkedHashMap<>();
        planItemsList.forEach(i -> planItemByOutput.put(i.getRobotRunOutputId(), i));
        Set<UUID> seen = new HashSet<>();
        List<String> hooks = new ArrayList<>();
        List<String> captionOpenings = new ArrayList<>();
        List<CampaignCopyItem> rows = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (WorkerCoordinatedCopyItemRequest item : request.items()) {
            if (item.outputId() == null || !byId.containsKey(item.outputId())) {
                throw new CopySetRejectedException("Coordinated copy references an unknown output");
            }
            if (!seen.add(item.outputId())) {
                throw new CopySetRejectedException("Coordinated copy references the same output twice");
            }
            RobotRunOutput output = byId.get(item.outputId());
            CampaignContentPlanItem planItem = planItemByOutput.get(item.outputId());
            if (planItem == null) {
                throw new CopySetRejectedException("Output has no corresponding campaign plan item");
            }
            String hook = boundedRequired(item.hook(), "hook", aiProperties.getMaxHookLength());
            String caption = boundedRequired(item.caption(), "caption", aiProperties.getMaxCaptionLength());
            List<String> hashtags = normalizeHashtags(item.hashtags());
            String shortTitle = boundedOptional(item.shortTitle(), aiProperties.getMaxShortTitleLength());
            String continuityNote = boundedOptional(item.continuityNote(), properties.getMaxContinuityNoteLength());
            for (String priorHook : hooks) {
                if (repetitionDetector.nearDuplicate(hook, priorHook, properties.getNearDuplicateHookThreshold())) {
                    throw new CopySetRejectedException("Coordinated copy proposed near-duplicate hooks across outputs");
                }
            }
            String opening = captionOpening(caption);
            for (String priorOpening : captionOpenings) {
                if (!opening.isBlank() && !priorOpening.isBlank()
                        && repetitionDetector.nearDuplicate(opening, priorOpening, properties.getDuplicateCaptionOpeningThreshold())) {
                    throw new CopySetRejectedException("Coordinated copy proposed repeated caption openings across outputs");
                }
            }
            hooks.add(hook);
            captionOpenings.add(opening);
            rows.add(new CampaignCopyItem(copySet, output.getId(), planItem.getId(), output.getSelectionOrder(),
                    hook, caption, hashtags, shortTitle, continuityNote, now));
        }
        // Item 48: a modest, safe check — every output sharing an identical, non-empty hashtag set is
        // almost certainly the model being lazy rather than a legitimate shared campaign tag choice.
        if (rows.size() > 1) {
            Set<Set<String>> distinctHashtagSets = new HashSet<>();
            for (CampaignCopyItem row : rows) {
                distinctHashtagSets.add(new HashSet<>(row.getHashtags()));
            }
            if (distinctHashtagSets.size() == 1 && !rows.get(0).getHashtags().isEmpty()) {
                throw new CopySetRejectedException("Coordinated copy proposed identical hashtag sets across all outputs");
            }
        }
        rows.sort(Comparator.comparingInt(CampaignCopyItem::getSequence));
        copyItems.saveAll(rows);
        return new ValidatedCopySet(seriesTitle);
    }

    /** Item 46: bounded, deterministic normalization — never full-caption or embedding comparison. */
    private String captionOpening(String caption) {
        String normalized = caption.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
        int window = properties.getCaptionOpeningWindowCharacters();
        return normalized.length() > window ? normalized.substring(0, window) : normalized;
    }

    /** Mirrors ContentSuggestionService's own hashtag normalization (trim/strip '#'/lowercase/dedup/bound) — kept local since that method is private to a different service. */
    private List<String> normalizeHashtags(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > aiProperties.getMaxHashtags() * 4) {
            throw new CopySetRejectedException("Too many hashtags returned");
        }
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String candidate : raw) {
            if (candidate == null) {
                continue;
            }
            String trimmed = candidate.trim();
            String stripped = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
            if (stripped.isBlank()) {
                continue;
            }
            for (int i = 0; i < stripped.length(); i++) {
                if (Character.isWhitespace(stripped.charAt(i))) {
                    throw new CopySetRejectedException("hashtag must not contain whitespace: " + stripped);
                }
            }
            String lower = stripped.toLowerCase(Locale.ROOT);
            if (lower.length() > aiProperties.getMaxHashtagLength()) {
                throw new CopySetRejectedException("hashtag exceeds maximum length: " + lower);
            }
            normalized.add(lower);
        }
        if (normalized.size() > aiProperties.getMaxHashtags()) {
            throw new CopySetRejectedException("Too many distinct hashtags returned");
        }
        return List.copyOf(normalized);
    }

    private String boundedRequired(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new CopySetRejectedException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new CopySetRejectedException(field + " exceeds the maximum length");
        }
        return trimmed;
    }

    private String boundedOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new CopySetRejectedException("guidance field exceeds the maximum length");
        }
        return trimmed;
    }

    private Job requireGenerationJob(Worker worker, UUID jobId) {
        Job job = jobService.requireJobForWorkerWorkspace(worker, jobId);
        if (job.getType() != JobType.GENERATE_COORDINATED_SOCIAL_COPY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not a coordinated copy generation");
        }
        if (job.getStatus() != JobStatus.RUNNING && job.getStatus() != JobStatus.ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not active");
        }
        return job;
    }

    private String safeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Coordinated copy generation failed";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /**
     * Deterministic SHA-256 over every input that could legitimately change
     * what a re-generation would produce (item 30): RobotRun/output/candidate
     * identity, the pinned CampaignContentPlan id+revision, each output's
     * plan item identity, Persona snapshot id, and config. RobotRunOutput
     * membership/order and the plan item set are immutable once frozen, so
     * — exactly like the campaign-plan fingerprint — this is a real,
     * testable safety net even though it is not expected to be reachable in
     * normal operation.
     */
    private String fingerprint(RobotRun run, List<RobotRunOutput> outputs, CampaignContentPlan plan,
            List<CampaignContentPlanItem> items, Persona persona, Map<String, Object> configSnapshot) {
        Map<UUID, CampaignContentPlanItem> byOutput = new LinkedHashMap<>();
        items.forEach(item -> byOutput.put(item.getRobotRunOutputId(), item));
        List<String> parts = new ArrayList<>();
        parts.add(run.getId().toString());
        parts.add(plan.getId().toString());
        parts.add(String.valueOf(plan.getRevision()));
        for (RobotRunOutput output : outputs) {
            CampaignContentPlanItem item = byOutput.get(output.getId());
            parts.add(output.getId() + ":" + output.getCandidate().getId() + ":" + output.getSelectionOrder()
                    + ":" + (item == null ? "" : item.getId()));
        }
        parts.add(persona == null ? "" : persona.getId().toString());
        parts.add(canonicalConfigSnapshot(configSnapshot));
        return sha256Hex(String.join("|", parts));
    }

    /**
     * A jsonb column round-trip does not preserve key insertion order (Postgres
     * canonicalizes jsonb key order on write), so {@code Map.toString()} on the
     * freshly-built snapshot at generation time and on {@code
     * copySet.getConfigSnapshot()} read back at apply time can legitimately
     * differ even though no key or value actually changed. Sorting keys first
     * makes the fingerprint depend only on content, never on map iteration
     * order — this was caught by real Docker/Postgres runtime acceptance
     * (a mocked-repository unit test cannot reproduce a real jsonb round trip).
     */
    String canonicalConfigSnapshot(Map<String, Object> configSnapshot) {
        return configSnapshot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private CampaignCopySetSummary toSummary(CampaignCopySet copySet) {
        List<CampaignCopyItemSummary> itemSummaries = copyItems.findByCopySetOrderBySequenceAsc(copySet).stream()
                .map(item -> new CampaignCopyItemSummary(item.getId(), item.getRobotRunOutputId(), item.getCampaignContentPlanItemId(),
                        item.getSequence(), item.getHook(), item.getCaption(), item.getHashtags(), item.getShortTitle(),
                        item.getContinuityNote(), item.getContentSuggestionId()))
                .toList();
        return new CampaignCopySetSummary(
                copySet.getId(), copySet.getRobotRun().getId(), copySet.getCampaignPlan().getId(), copySet.getCampaignPlan().getRevision(),
                copySet.getRevision(), copySet.isCurrent(), copySet.getStatus(), copySet.getGeneratorVersion(), copySet.getProvider(),
                copySet.getModel(), copySet.getPromptVersion(), copySet.getSeriesTitle(), copySet.getSharedFraming(),
                copySet.getInputFingerprint(), copySet.getConfigSnapshot(), copySet.getFailureCode(), copySet.getFailureMessage(), false,
                copySet.getCreatedAt(), copySet.getUpdatedAt(), copySet.getCompletedAt(), copySet.getAppliedAt(), copySet.getAppliedByUserId(),
                copySet.getRejectedAt(), copySet.getRejectedByUserId(), itemSummaries);
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }
}
