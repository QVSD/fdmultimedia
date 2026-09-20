package com.fdmultimedia.api.experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Item 85 (assignment is outcome-blind) is also verified by inspection: this
 * class's only collaborators are {@link ExperimentRepository}, {@link
 * ExperimentVariantRepository}, and {@link ExperimentAssignmentRepository} —
 * no {@code PublicationAnalyticsSnapshot}, dashboard, or insight type is
 * imported anywhere in {@code ExperimentAssignmentService}.
 */
class ExperimentAssignmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    private final ExperimentRepository experiments = mock(ExperimentRepository.class);
    private final ExperimentVariantRepository variants = mock(ExperimentVariantRepository.class);
    private final ExperimentAssignmentRepository assignments = mock(ExperimentAssignmentRepository.class);
    private final ExperimentAssignmentService service = new ExperimentAssignmentService(experiments, variants, assignments);

    private Workspace workspace;
    private AppUser owner;
    private Experiment experiment;
    private ExperimentVariant variantA;
    private ExperimentVariant variantB;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        experiment = new Experiment(workspace, "Persona test", null, "hypothesis", ExperimentFactor.PERSONA,
                DashboardQuery.Window.H72, DashboardQuery.Metric.VIEWS, owner, NOW);
        experiment.activate(NOW);
        variantA = new ExperimentVariant(experiment, ExperimentVariantKey.A, "Variant A", UUID.randomUUID(), NOW);
        variantB = new ExperimentVariant(experiment, ExperimentVariantKey.B, "Variant B", UUID.randomUUID(), NOW);
        variantA.freeze(fakeSnapshot(variantA), null, null);
        variantB.freeze(fakeSnapshot(variantB), null, null);
        when(experiments.findByWorkspaceAndIdForUpdate(workspace, experiment.getId())).thenReturn(Optional.of(experiment));
        when(variants.findByExperimentOrderByVariantKeyAsc(experiment)).thenReturn(List.of(variantA, variantB));
        when(assignments.save(any(ExperimentAssignment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private com.fdmultimedia.api.personas.PersonaSnapshot fakeSnapshot(ExperimentVariant variant) {
        return new com.fdmultimedia.api.personas.PersonaSnapshot(variant.getPersonaId(), variant.getLabel(), null, "voice", null, null, null, null);
    }

    @Test
    void firstAssignmentGoesToVariantAOnATie() {
        when(assignments.countGroupedByVariant(experiment)).thenReturn(List.of());

        ExperimentAssignment assignment = service.assign(workspace, experiment.getId(), UUID.randomUUID(), NOW);

        assertThat(assignment.getExperimentVariant()).isEqualTo(variantA);
    }

    @Test
    void assignsToTheStrictlyLeastAssignedVariant() {
        when(assignments.countGroupedByVariant(experiment)).thenReturn(
                List.of(new Object[] {variantA.getId(), 3L}, new Object[] {variantB.getId(), 1L}));

        ExperimentAssignment assignment = service.assign(workspace, experiment.getId(), UUID.randomUUID(), NOW);

        assertThat(assignment.getExperimentVariant()).isEqualTo(variantB);
    }

    @Test
    void tenAssignmentsStayBalancedWithADifferenceOfAtMostOne() {
        java.util.Map<UUID, Long> counts = new java.util.HashMap<>();
        counts.put(variantA.getId(), 0L);
        counts.put(variantB.getId(), 0L);
        when(assignments.countGroupedByVariant(experiment)).thenAnswer(inv -> List.of(
                new Object[] {variantA.getId(), counts.get(variantA.getId())},
                new Object[] {variantB.getId(), counts.get(variantB.getId())}));

        for (int i = 0; i < 10; i++) {
            ExperimentAssignment assignment = service.assign(workspace, experiment.getId(), UUID.randomUUID(), NOW);
            counts.merge(assignment.getExperimentVariant().getId(), 1L, Long::sum);
        }

        assertThat(Math.abs(counts.get(variantA.getId()) - counts.get(variantB.getId()))).isLessThanOrEqualTo(1);
        assertThat(counts.get(variantA.getId())).isEqualTo(5L);
        assertThat(counts.get(variantB.getId())).isEqualTo(5L);
    }

    @Test
    void rejectsAssignmentWhenTheExperimentIsNotActive() {
        experiment.pause(NOW);

        assertThatThrownBy(() -> service.assign(workspace, experiment.getId(), UUID.randomUUID(), NOW))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void assignmentCarriesTheFactorAndFrozenTreatmentIdentity() {
        when(assignments.countGroupedByVariant(experiment)).thenReturn(List.of());
        UUID robotRunId = UUID.randomUUID();

        ExperimentAssignment assignment = service.assign(workspace, experiment.getId(), robotRunId, NOW);

        assertThat(assignment.getRobotRunId()).isEqualTo(robotRunId);
        assertThat(assignment.getFactor()).isEqualTo(ExperimentFactor.PERSONA);
        assertThat(assignment.getAssignmentStrategy()).isEqualTo(AssignmentStrategy.DETERMINISTIC_BALANCED_V1);
        assertThat(assignment.getFactorValueId()).isEqualTo(variantA.getPersonaId());
    }
}
