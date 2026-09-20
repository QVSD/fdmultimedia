package com.fdmultimedia.api.experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ExperimentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final ExperimentRepository experiments = mock(ExperimentRepository.class);
    private final ExperimentVariantRepository variants = mock(ExperimentVariantRepository.class);
    private final ExperimentAssignmentRepository assignments = mock(ExperimentAssignmentRepository.class);
    private final PersonaRepository personas = mock(PersonaRepository.class);
    private final ExperimentService service = new ExperimentService(
            authService, experiments, variants, assignments, personas, Clock.fixed(NOW, ZoneOffset.UTC));

    private final List<ExperimentVariant> savedVariants = new ArrayList<>();

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        when(experiments.save(any(Experiment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(variants.save(any(ExperimentVariant.class))).thenAnswer(inv -> {
            ExperimentVariant v = inv.getArgument(0);
            savedVariants.add(v);
            return v;
        });
        when(variants.findByExperimentOrderByVariantKeyAsc(any(Experiment.class))).thenAnswer(inv -> List.copyOf(savedVariants));
        when(variants.findByExperimentAndVariantKey(any(Experiment.class), any(ExperimentVariantKey.class))).thenAnswer(inv -> {
            ExperimentVariantKey key = inv.getArgument(1);
            return savedVariants.stream().filter(v -> v.getVariantKey() == key).findFirst();
        });
        when(assignments.countGroupedByVariant(any())).thenReturn(List.of());
    }

    private Persona activePersona(String name) {
        return new Persona(workspace, name, null, SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL,
                "General audience", "Warm, direct voice", null, null, null, null, owner, NOW);
    }

    private CreateExperimentRequest validCreateRequest(Persona a, Persona b) {
        return new CreateExperimentRequest("Persona A/B test", "desc", "Bolder tone drives more saves",
                ExperimentFactor.PERSONA, "H72", "VIEWS", a.getId(), "Variant A", b.getId(), "Variant B");
    }

    @Test
    void createBuildsDraftExperimentWithTwoDistinctVariants() {
        Persona a = activePersona("Friendly");
        Persona b = activePersona("Bold");
        when(personas.findByWorkspaceAndId(workspace, a.getId())).thenReturn(Optional.of(a));
        when(personas.findByWorkspaceAndId(workspace, b.getId())).thenReturn(Optional.of(b));

        ExperimentSummary summary = service.create(user, validCreateRequest(a, b));

        assertThat(summary.status()).isEqualTo(ExperimentStatus.DRAFT);
        assertThat(summary.factor()).isEqualTo(ExperimentFactor.PERSONA);
        assertThat(summary.variants()).hasSize(2);
        assertThat(summary.variants().get(0).variantKey()).isEqualTo(ExperimentVariantKey.A);
        assertThat(summary.variants().get(0).personaId()).isEqualTo(a.getId());
        assertThat(summary.variants().get(1).variantKey()).isEqualTo(ExperimentVariantKey.B);
        assertThat(summary.variants().get(1).personaId()).isEqualTo(b.getId());
        assertThat(summary.variants()).allMatch(v -> !v.frozen());
    }

    @Test
    void createRejectsWhenBothVariantsUseTheSamePersona() {
        Persona a = activePersona("Friendly");
        when(personas.findByWorkspaceAndId(workspace, a.getId())).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.create(user, new CreateExperimentRequest(
                "Bad experiment", null, "hypothesis", ExperimentFactor.PERSONA, "H72", "VIEWS",
                a.getId(), "A", a.getId(), "B")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createRejectsAnArchivedPersona() {
        Persona archived = activePersona("Archived");
        archived.archive(NOW);
        Persona active = activePersona("Active");
        when(personas.findByWorkspaceAndId(workspace, archived.getId())).thenReturn(Optional.of(archived));
        when(personas.findByWorkspaceAndId(workspace, active.getId())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.create(user, validCreateRequest(archived, active)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createRejectsLatestAsTheTargetObservationWindow() {
        Persona a = activePersona("A");
        Persona b = activePersona("B");
        when(personas.findByWorkspaceAndId(workspace, a.getId())).thenReturn(Optional.of(a));
        when(personas.findByWorkspaceAndId(workspace, b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.create(user, new CreateExperimentRequest(
                "Bad window", null, "hypothesis", ExperimentFactor.PERSONA, "LATEST", "VIEWS",
                a.getId(), "A", b.getId(), "B")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getForThrowsNotFoundForAnExperimentInAnotherWorkspace() {
        when(experiments.findByWorkspaceAndId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFor(user, java.util.UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateEditsDraftFieldsAndSwapsVariantPersonas() {
        Persona a = activePersona("Friendly");
        Persona b = activePersona("Bold");
        when(personas.findByWorkspaceAndId(workspace, a.getId())).thenReturn(Optional.of(a));
        when(personas.findByWorkspaceAndId(workspace, b.getId())).thenReturn(Optional.of(b));
        ExperimentSummary created = service.create(user, validCreateRequest(a, b));
        Experiment experiment = experimentEntity(created.id());
        when(experiments.findByWorkspaceAndIdForUpdate(workspace, created.id())).thenReturn(Optional.of(experiment));

        Persona c = activePersona("Calm");
        when(personas.findByWorkspaceAndId(workspace, c.getId())).thenReturn(Optional.of(c));
        ExperimentSummary updated = service.update(user, created.id(), new UpdateExperimentRequest(
                "Renamed", "new desc", "updated hypothesis", "D7", "TOTAL_INTERACTIONS", c.getId(), "Variant A2", b.getId(), "Variant B"));

        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.targetObservationWindow()).isEqualTo("D7");
        assertThat(updated.variants().get(0).personaId()).isEqualTo(c.getId());
    }

    @Test
    void updateRejectsOnceTheExperimentIsNoLongerDraft() {
        Experiment experiment = new Experiment(workspace, "E", null, "hyp", ExperimentFactor.PERSONA,
                DashboardQuery.Window.H72, DashboardQuery.Metric.VIEWS, owner, NOW);
        experiment.activate(NOW);
        when(experiments.findByWorkspaceAndIdForUpdate(any(), any())).thenReturn(Optional.of(experiment));
        Persona a = activePersona("A");
        Persona b = activePersona("B");
        when(personas.findByWorkspaceAndId(workspace, a.getId())).thenReturn(Optional.of(a));
        when(personas.findByWorkspaceAndId(workspace, b.getId())).thenReturn(Optional.of(b));
        when(variants.findByExperimentAndVariantKey(any(), any())).thenReturn(Optional.of(
                new ExperimentVariant(experiment, ExperimentVariantKey.A, "A", a.getId(), NOW)));

        assertThatThrownBy(() -> service.update(user, experiment.getId(), new UpdateExperimentRequest(
                "x", null, "y", "H72", "VIEWS", a.getId(), null, b.getId(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void activateFreezesVariantSnapshotsAndTransitionsToActive() {
        Persona a = activePersona("Friendly");
        Persona b = activePersona("Bold");
        when(personas.findByWorkspaceAndId(workspace, a.getId())).thenReturn(Optional.of(a));
        when(personas.findByWorkspaceAndId(workspace, b.getId())).thenReturn(Optional.of(b));
        ExperimentSummary created = service.create(user, validCreateRequest(a, b));
        Experiment experiment = experimentEntity(created.id());
        when(experiments.findByWorkspaceAndIdForUpdate(workspace, created.id())).thenReturn(Optional.of(experiment));

        ExperimentSummary activated = service.activate(user, created.id());

        assertThat(activated.status()).isEqualTo(ExperimentStatus.ACTIVE);
        assertThat(activated.activatedAt()).isEqualTo(NOW);
        assertThat(activated.variants()).allMatch(ExperimentVariantSummary::frozen);
        assertThat(activated.variants().get(0).personaNameSnapshot()).isEqualTo("Friendly");
        assertThat(activated.variants().get(1).personaNameSnapshot()).isEqualTo("Bold");
    }

    @Test
    void activateRejectsWhenAPersonaWasArchivedSinceDraft() {
        Persona a = activePersona("Friendly");
        Persona b = activePersona("Bold");
        when(personas.findByWorkspaceAndId(workspace, a.getId())).thenReturn(Optional.of(a));
        when(personas.findByWorkspaceAndId(workspace, b.getId())).thenReturn(Optional.of(b));
        ExperimentSummary created = service.create(user, validCreateRequest(a, b));
        Experiment experiment = experimentEntity(created.id());
        when(experiments.findByWorkspaceAndIdForUpdate(workspace, created.id())).thenReturn(Optional.of(experiment));
        b.archive(NOW);

        assertThatThrownBy(() -> service.activate(user, created.id()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
        assertThat(experiment.getStatus()).isEqualTo(ExperimentStatus.DRAFT);
    }

    @Test
    void fullLifecycleTransitionsSucceedInOrder() {
        Experiment experiment = new Experiment(workspace, "E", null, "hyp", ExperimentFactor.PERSONA,
                DashboardQuery.Window.H72, DashboardQuery.Metric.VIEWS, owner, NOW);
        when(experiments.findByWorkspaceAndIdForUpdate(any(), any())).thenReturn(Optional.of(experiment));
        experiment.activate(NOW);

        assertThat(service.pause(user, experiment.getId()).status()).isEqualTo(ExperimentStatus.PAUSED);
        assertThat(service.resume(user, experiment.getId()).status()).isEqualTo(ExperimentStatus.ACTIVE);
        assertThat(service.complete(user, experiment.getId()).status()).isEqualTo(ExperimentStatus.COMPLETED);
    }

    @Test
    void terminalExperimentsRejectAnyFurtherTransition() {
        Experiment experiment = new Experiment(workspace, "E", null, "hyp", ExperimentFactor.PERSONA,
                DashboardQuery.Window.H72, DashboardQuery.Metric.VIEWS, owner, NOW);
        when(experiments.findByWorkspaceAndIdForUpdate(any(), any())).thenReturn(Optional.of(experiment));
        experiment.cancel(NOW);

        assertThatThrownBy(() -> service.resume(user, experiment.getId()))
                .isInstanceOf(ResponseStatusException.class).extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(() -> service.cancel(user, experiment.getId()))
                .isInstanceOf(ResponseStatusException.class).extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(() -> service.pause(user, experiment.getId()))
                .isInstanceOf(ResponseStatusException.class).extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void assertRobotAttachableAllowsDraftActiveAndPausedButNotTerminal() {
        Experiment draft = new Experiment(workspace, "E", null, "hyp", ExperimentFactor.PERSONA,
                DashboardQuery.Window.H72, DashboardQuery.Metric.VIEWS, owner, NOW);
        when(experiments.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));

        service.assertRobotAttachable(workspace, draft.getId(), true);

        draft.activate(NOW);
        service.assertRobotAttachable(workspace, draft.getId(), true);
        draft.pause(NOW);
        service.assertRobotAttachable(workspace, draft.getId(), true);
        draft.resume(NOW);
        draft.complete(NOW);

        assertThatThrownBy(() -> service.assertRobotAttachable(workspace, draft.getId(), true))
                .isInstanceOf(ResponseStatusException.class).extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void assertRobotAttachableRejectsAnAiPolicyThatDoesNotConsumeAPersona() {
        Experiment experiment = new Experiment(workspace, "E", null, "hyp", ExperimentFactor.PERSONA,
                DashboardQuery.Window.H72, DashboardQuery.Metric.VIEWS, owner, NOW);
        when(experiments.findByWorkspaceAndId(workspace, experiment.getId())).thenReturn(Optional.of(experiment));

        assertThatThrownBy(() -> service.assertRobotAttachable(workspace, experiment.getId(), false))
                .isInstanceOf(ResponseStatusException.class).extracting("statusCode").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void assertActiveForAssignmentRejectsADraftOrPausedExperiment() {
        Experiment experiment = new Experiment(workspace, "E", null, "hyp", ExperimentFactor.PERSONA,
                DashboardQuery.Window.H72, DashboardQuery.Metric.VIEWS, owner, NOW);
        when(experiments.findByWorkspaceAndId(workspace, experiment.getId())).thenReturn(Optional.of(experiment));

        assertThatThrownBy(() -> service.assertActiveForAssignment(workspace, experiment.getId()))
                .isInstanceOf(ResponseStatusException.class).extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);

        experiment.activate(NOW);
        service.assertActiveForAssignment(workspace, experiment.getId());
    }

    @Test
    void resolveTreatmentBuildsFromTheFrozenVariantSnapshotOnly() {
        Persona a = activePersona("Friendly");
        when(personas.findByWorkspaceAndId(workspace, a.getId())).thenReturn(Optional.of(a));
        Experiment experiment = new Experiment(workspace, "E", null, "hyp", ExperimentFactor.PERSONA,
                DashboardQuery.Window.H72, DashboardQuery.Metric.VIEWS, owner, NOW);
        ExperimentVariant variant = new ExperimentVariant(experiment, ExperimentVariantKey.A, "Variant A", a.getId(), NOW);
        variant.freeze(a.toSnapshot(), a.getDefaultLanguage(), a.getDefaultTone());
        java.util.UUID variantId = java.util.UUID.randomUUID();
        when(variants.findById(variantId)).thenReturn(Optional.of(variant));

        ExperimentTreatment treatment = service.resolveTreatment(experiment.getId(), java.util.UUID.randomUUID(), variantId);

        assertThat(treatment.personaSnapshot().personaName()).isEqualTo("Friendly");
        assertThat(treatment.defaultLanguage()).isEqualTo(a.getDefaultLanguage());
        assertThat(treatment.defaultTone()).isEqualTo(a.getDefaultTone());
    }

    /** Simulates the real repository behavior for a freshly-created Experiment: re-fetching by id returns the same in-memory instance. */
    private Experiment experimentEntity(java.util.UUID id) {
        // The DRAFT Experiment created via service.create() is the exact instance already stored
        // by the "save echoes its argument" stub above; recover it through the variants it owns.
        return savedVariants.get(0).getExperiment();
    }
}
