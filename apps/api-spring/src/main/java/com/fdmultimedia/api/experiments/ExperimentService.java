package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.personas.PersonaStatus;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Experiment definition and lifecycle (items 5/6/36/37/50). Never touches
 * RobotRun assignment (see {@link ExperimentAssignmentService}) and never
 * reads any performance analytics (see {@link ExperimentOutcomeService}) —
 * this class owns only the controlled-assignment contract itself.
 */
@Service
public class ExperimentService {

    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final int MAX_HYPOTHESIS_LENGTH = 2000;
    private static final int MAX_ASSIGNMENTS_LISTED = 200;

    private final AuthService authService;
    private final ExperimentRepository experiments;
    private final ExperimentVariantRepository variants;
    private final ExperimentAssignmentRepository assignments;
    private final PersonaRepository personas;
    private final Clock clock;

    public ExperimentService(
            AuthService authService,
            ExperimentRepository experiments,
            ExperimentVariantRepository variants,
            ExperimentAssignmentRepository assignments,
            PersonaRepository personas,
            Clock clock) {
        this.authService = authService;
        this.experiments = experiments;
        this.variants = variants;
        this.assignments = assignments;
        this.personas = personas;
        this.clock = clock;
    }

    @Transactional
    public ExperimentSummary create(AuthenticatedUser principal, CreateExperimentRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        String name = validateName(request.name());
        String description = validateDescription(request.description());
        String hypothesis = validateHypothesis(request.hypothesis());
        ExperimentFactor factor = request.factor() == null ? ExperimentFactor.PERSONA : request.factor();
        DashboardQuery.Window window = validateWindow(request.targetObservationWindow());
        DashboardQuery.Metric metric = validateMetric(request.primaryMetric());

        Persona personaA = resolvePersonaForVariant(workspace, request.variantAPersonaId());
        Persona personaB = resolvePersonaForVariant(workspace, request.variantBPersonaId());
        requireDistinct(personaA, personaB);

        Instant now = Instant.now(clock);
        Experiment experiment = experiments.save(
                new Experiment(workspace, name, description, hypothesis, factor, window, metric, membership.getUser(), now));
        variants.save(new ExperimentVariant(experiment, ExperimentVariantKey.A,
                variantLabel(request.variantALabel(), personaA), personaA.getId(), now));
        variants.save(new ExperimentVariant(experiment, ExperimentVariantKey.B,
                variantLabel(request.variantBLabel(), personaB), personaB.getId(), now));
        return toSummary(experiment);
    }

    @Transactional(readOnly = true)
    public List<ExperimentSummary> list(AuthenticatedUser principal) {
        Workspace workspace = currentWorkspace(principal);
        return experiments.findByWorkspaceOrderByCreatedAtDesc(workspace).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public ExperimentSummary getFor(AuthenticatedUser principal, UUID experimentId) {
        Workspace workspace = currentWorkspace(principal);
        return toSummary(requireExperiment(workspace, experimentId));
    }

    @Transactional
    public ExperimentSummary update(AuthenticatedUser principal, UUID experimentId, UpdateExperimentRequest request) {
        Workspace workspace = currentWorkspace(principal);
        Experiment experiment = experiments.findByWorkspaceAndIdForUpdate(workspace, experimentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found"));
        String name = validateName(request.name());
        String description = validateDescription(request.description());
        String hypothesis = validateHypothesis(request.hypothesis());
        DashboardQuery.Window window = validateWindow(request.targetObservationWindow());
        DashboardQuery.Metric metric = validateMetric(request.primaryMetric());
        Persona personaA = resolvePersonaForVariant(workspace, request.variantAPersonaId());
        Persona personaB = resolvePersonaForVariant(workspace, request.variantBPersonaId());
        requireDistinct(personaA, personaB);

        Instant now = Instant.now(clock);
        try {
            experiment.updateDraft(name, description, hypothesis, window, metric, now);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
        ExperimentVariant variantA = requireVariant(experiment, ExperimentVariantKey.A);
        ExperimentVariant variantB = requireVariant(experiment, ExperimentVariantKey.B);
        variantA.updateDraftPersona(personaA.getId(), variantLabel(request.variantALabel(), personaA));
        variantB.updateDraftPersona(personaB.getId(), variantLabel(request.variantBLabel(), personaB));
        return toSummary(experiment);
    }

    /** Freezes the semantic definition (item 35) — variant treatment snapshots included — and transitions to ACTIVE atomically (item 79). */
    @Transactional
    public ExperimentSummary activate(AuthenticatedUser principal, UUID experimentId) {
        Workspace workspace = currentWorkspace(principal);
        Experiment experiment = experiments.findByWorkspaceAndIdForUpdate(workspace, experimentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found"));
        List<ExperimentVariant> variantRows = variants.findByExperimentOrderByVariantKeyAsc(experiment);
        if (variantRows.size() != 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Experiment must have exactly two variants (A and B)");
        }
        ExperimentVariant variantA = variantRows.get(0);
        ExperimentVariant variantB = variantRows.get(1);
        if (variantA.getPersonaId().equals(variantB.getPersonaId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Variant A and Variant B must use distinct Personas");
        }
        Instant now = Instant.now(clock);
        for (ExperimentVariant variant : variantRows) {
            Persona persona = personas.findByWorkspaceAndId(workspace, variant.getPersonaId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "PERSONA_UNAVAILABLE"));
            if (persona.getStatus() != PersonaStatus.ACTIVE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "PERSONA_ARCHIVED");
            }
            variant.freeze(persona.toSnapshot(), persona.getDefaultLanguage(), persona.getDefaultTone());
        }
        try {
            experiment.activate(now);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
        return toSummary(experiment);
    }

    @Transactional
    public ExperimentSummary pause(AuthenticatedUser principal, UUID experimentId) {
        return transition(principal, experimentId, Experiment::pause);
    }

    @Transactional
    public ExperimentSummary resume(AuthenticatedUser principal, UUID experimentId) {
        return transition(principal, experimentId, Experiment::resume);
    }

    @Transactional
    public ExperimentSummary complete(AuthenticatedUser principal, UUID experimentId) {
        return transition(principal, experimentId, Experiment::complete);
    }

    @Transactional
    public ExperimentSummary cancel(AuthenticatedUser principal, UUID experimentId) {
        return transition(principal, experimentId, Experiment::cancel);
    }

    private ExperimentSummary transition(AuthenticatedUser principal, UUID experimentId, java.util.function.BiConsumer<Experiment, Instant> action) {
        Workspace workspace = currentWorkspace(principal);
        Experiment experiment = experiments.findByWorkspaceAndIdForUpdate(workspace, experimentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found"));
        try {
            action.accept(experiment, Instant.now(clock));
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
        return toSummary(experiment);
    }

    @Transactional(readOnly = true)
    public List<ExperimentAssignmentSummary> assignmentsFor(AuthenticatedUser principal, UUID experimentId) {
        Workspace workspace = currentWorkspace(principal);
        Experiment experiment = requireExperiment(workspace, experimentId);
        return assignments.findByExperimentOrderByAssignedAtDesc(experiment, PageRequest.of(0, MAX_ASSIGNMENTS_LISTED)).stream()
                .map(a -> new ExperimentAssignmentSummary(a.getId(), experiment.getId(), a.getExperimentVariant().getId(),
                        a.getExperimentVariant().getVariantKey(), a.getRobotRunId(), a.getAssignedAt(),
                        a.getAssignmentStrategy(), a.getFactor(), a.getFactorValueNameSnapshot()))
                .toList();
    }

    /**
     * Item 13/14/54/55: a Robot may reference a DRAFT, ACTIVE, or PAUSED
     * Experiment (flexible setup order), never a terminal one, and only when
     * its AI policy actually consumes a Persona treatment. Called from
     * {@code RobotService} with a plain boolean rather than the
     * {@code RobotAiPolicy} enum itself, so this package never depends on
     * {@code com.fdmultimedia.api.robots}.
     */
    @Transactional(readOnly = true)
    public void assertRobotAttachable(Workspace workspace, UUID experimentId, boolean aiPolicyConsumesPersona) {
        if (experimentId == null) {
            return;
        }
        Experiment experiment = requireExperiment(workspace, experimentId);
        if (experiment.isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EXPERIMENT_TERMINAL_CANNOT_ATTACH");
        }
        if (experiment.getFactor() == ExperimentFactor.PERSONA && !aiPolicyConsumesPersona) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ROBOT_AI_POLICY_INCOMPATIBLE_WITH_EXPERIMENT");
        }
    }

    /** Optimistic (non-locking) pre-check used by {@code RobotAutomationDispatchService} before starting a run (item 24). */
    @Transactional(readOnly = true)
    public void assertActiveForAssignment(Workspace workspace, UUID experimentId) {
        Experiment experiment = requireExperiment(workspace, experimentId);
        if (experiment.getStatus() != ExperimentStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ROBOT_EXPERIMENT_NOT_ACTIVE");
        }
    }

    /** Item 28/86: builds the frozen treatment from the variant's own snapshot columns only, never a live Persona lookup. */
    @Transactional(readOnly = true)
    public ExperimentTreatment resolveTreatment(UUID experimentId, UUID experimentAssignmentId, UUID experimentVariantId) {
        ExperimentVariant variant = variants.findById(experimentVariantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "EXPERIMENT_VARIANT_UNAVAILABLE"));
        return new ExperimentTreatment(experimentId, experimentAssignmentId, experimentVariantId,
                variant.toPersonaSnapshot(), variant.getPersonaDefaultLanguageSnapshot(), variant.getPersonaDefaultToneSnapshot());
    }

    private Persona resolvePersonaForVariant(Workspace workspace, UUID personaId) {
        Persona persona = personas.findByWorkspaceAndId(workspace, personaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Persona not found"));
        if (persona.getStatus() != PersonaStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PERSONA_ARCHIVED");
        }
        return persona;
    }

    private void requireDistinct(Persona personaA, Persona personaB) {
        if (personaA.getId().equals(personaB.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Variant A and Variant B must use distinct Personas");
        }
    }

    private String variantLabel(String requested, Persona persona) {
        return requested == null || requested.isBlank() ? persona.getName() : requested.trim();
    }

    private ExperimentVariant requireVariant(Experiment experiment, ExperimentVariantKey key) {
        return variants.findByExperimentAndVariantKey(experiment, key)
                .orElseThrow(() -> new IllegalStateException("Experiment is missing its " + key + " variant"));
    }

    private Experiment requireExperiment(Workspace workspace, UUID experimentId) {
        return experiments.findByWorkspaceAndId(workspace, experimentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found"));
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
        if (description == null || description.isBlank()) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return trimmed;
    }

    private String validateHypothesis(String hypothesis) {
        if (hypothesis == null || hypothesis.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hypothesis is required");
        }
        String trimmed = hypothesis.trim();
        if (trimmed.length() > MAX_HYPOTHESIS_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hypothesis must be at most " + MAX_HYPOTHESIS_LENGTH + " characters");
        }
        return trimmed;
    }

    /** LATEST is deliberately rejected (item 38) — unsuitable as a controlled experiment's fixed outcome definition. */
    private DashboardQuery.Window validateWindow(String value) {
        try {
            DashboardQuery.Window window = DashboardQuery.Window.valueOf(value);
            if (window == DashboardQuery.Window.LATEST) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetObservationWindow must be H24, H72, or D7");
            }
            return window;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid targetObservationWindow");
        }
    }

    private DashboardQuery.Metric validateMetric(String value) {
        try {
            return DashboardQuery.Metric.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid primaryMetric");
        }
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private ExperimentSummary toSummary(Experiment experiment) {
        List<ExperimentVariant> variantRows = variants.findByExperimentOrderByVariantKeyAsc(experiment);
        Map<UUID, Long> counts = countsByVariant(experiment);
        List<ExperimentVariantSummary> variantSummaries = variantRows.stream()
                .sorted(Comparator.comparing(ExperimentVariant::getVariantKey))
                .map(v -> new ExperimentVariantSummary(v.getId(), v.getVariantKey(), v.getLabel(), v.getPersonaId(),
                        v.getPersonaNameSnapshot(), v.isFrozen(), counts.getOrDefault(v.getId(), 0L)))
                .toList();
        return new ExperimentSummary(experiment.getId(), experiment.getName(), experiment.getDescription(),
                experiment.getHypothesis(), experiment.getFactor(), experiment.getStatus(), experiment.getAssignmentStrategy(),
                experiment.getTargetObservationWindow().name(), experiment.getPrimaryMetric().name(), variantSummaries,
                experiment.getCreatedAt(), experiment.getUpdatedAt(), experiment.getActivatedAt(), experiment.getStoppedAt());
    }

    private Map<UUID, Long> countsByVariant(Experiment experiment) {
        Map<UUID, Long> counts = new java.util.HashMap<>();
        for (Object[] row : assignments.countGroupedByVariant(experiment)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }
}
