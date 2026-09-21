package com.fdmultimedia.api.experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.personas.PersonaStatus;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotRepository;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class DecisionApplicationServiceTest {
    private final AuthService auth = mock(AuthService.class);
    private final ExperimentRepository experiments = mock(ExperimentRepository.class);
    private final ExperimentVariantRepository variants = mock(ExperimentVariantRepository.class);
    private final ExperimentDecisionService decisions = mock(ExperimentDecisionService.class);
    private final RobotRepository robots = mock(RobotRepository.class);
    private final PersonaRepository personas = mock(PersonaRepository.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final DecisionApplicationProperties properties = new DecisionApplicationProperties();
    private final DecisionApplicationService service = new DecisionApplicationService(auth, experiments, variants,
            decisions, robots, personas, jdbc, properties, Clock.systemUTC());
    private final AuthenticatedUser user = mock(AuthenticatedUser.class);
    private final Workspace workspace = mock(Workspace.class);
    private final Experiment experiment = mock(Experiment.class);
    private final Robot robot = mock(Robot.class);
    private final ExperimentVariant variant = mock(ExperimentVariant.class);
    private final Persona current = mock(Persona.class);
    private final Persona target = mock(Persona.class);
    private final UUID experimentId = UUID.randomUUID();
    private final UUID decisionId = UUID.randomUUID();
    private final UUID robotId = UUID.randomUUID();
    private final UUID currentId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        WorkspaceMembership membership = mock(WorkspaceMembership.class);
        when(auth.currentMembershipFor(user)).thenReturn(membership); when(membership.getWorkspace()).thenReturn(workspace);
        when(workspace.getId()).thenReturn(UUID.randomUUID());
        when(experiments.findByWorkspaceAndId(workspace, experimentId)).thenReturn(Optional.of(experiment));
        when(experiment.getId()).thenReturn(experimentId); when(experiment.getName()).thenReturn("Persona test");
        when(experiment.getWorkspace()).thenReturn(workspace); when(experiment.getFactor()).thenReturn(ExperimentFactor.PERSONA);
        when(experiment.getStatus()).thenReturn(ExperimentStatus.ACTIVE);
        when(decisions.detail(user, experimentId, decisionId)).thenReturn(decision("SELECT_VARIANT_A", "A", "READY_FOR_REVIEW"));
        when(robots.findByWorkspaceAndId(workspace, robotId)).thenReturn(Optional.of(robot));
        when(robot.getId()).thenReturn(robotId); when(robot.getName()).thenReturn("Publisher"); when(robot.getWorkspace()).thenReturn(workspace);
        when(robot.getExperimentId()).thenReturn(experimentId); when(robot.getPersona()).thenReturn(current); when(robot.getUpdatedAt()).thenReturn(Instant.EPOCH);
        when(current.getId()).thenReturn(currentId); when(current.getName()).thenReturn("Current"); when(current.getStatus()).thenReturn(PersonaStatus.ACTIVE);
        when(variants.findByExperimentAndVariantKey(experiment, ExperimentVariantKey.A)).thenReturn(Optional.of(variant));
        when(variant.getPersonaId()).thenReturn(targetId); when(variant.getPersonaNameSnapshot()).thenReturn("Target frozen");
        when(personas.findByWorkspaceAndId(workspace, targetId)).thenReturn(Optional.of(target));
        when(target.getId()).thenReturn(targetId); when(target.getName()).thenReturn("Target live"); when(target.getStatus()).thenReturn(PersonaStatus.ACTIVE);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(0L);
    }

    @Test
    void previewIsReadOnlyAndUsesFrozenVariantKeyWithExactPersonaDiff() {
        DecisionApplicationPreview result = service.preview(user, experimentId, decisionId, robotId);
        assertThat(result.eligible()).isTrue(); assertThat(result.currentPersona().id()).isEqualTo(currentId);
        assertThat(result.targetPersona().id()).isEqualTo(targetId); assertThat(result.warnings()).contains("EXPERIMENT_STILL_ACTIVE");
        assertThat(result.previewFingerprint()).hasSize(64); verify(robots, never()).save(any());
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void archivedLiveTargetBlocksEvenThoughFrozenTreatmentRemainsValidHistorically() {
        when(target.getStatus()).thenReturn(PersonaStatus.ARCHIVED);
        assertThat(service.preview(user, experimentId, decisionId, robotId).blockingReasons()).contains("TARGET_PERSONA_ARCHIVED");
    }

    @Test
    void unsupportedDecisionAndCancelledExperimentAreBlockedInStableOrder() {
        when(decisions.detail(user, experimentId, decisionId)).thenReturn(decision("INCONCLUSIVE", null, "NOT_READY"));
        when(experiment.getStatus()).thenReturn(ExperimentStatus.CANCELLED);
        assertThat(service.preview(user, experimentId, decisionId, robotId).blockingReasons())
                .startsWith("UNSUPPORTED_DECISION", "EXPERIMENT_CANCELLED");
    }

    @Test
    void robotStateChangeChangesFingerprint() {
        String first = service.preview(user, experimentId, decisionId, robotId).previewFingerprint();
        when(robot.getUpdatedAt()).thenReturn(Instant.EPOCH.plusSeconds(1));
        assertThat(service.preview(user, experimentId, decisionId, robotId).previewFingerprint()).isNotEqualTo(first);
    }

    private ExperimentDecisionRecord decision(String type, String selected, String readiness) {
        return new ExperimentDecisionRecord(decisionId, experimentId, UUID.randomUUID(), type, selected, "reason",
                UUID.randomUUID(), Instant.EPOCH, "EXPERIMENT_GUARDRAILS_V1", "EXPERIMENT_ANALYSIS_V1", "ACTIVE",
                "VIEWS", "H24", null, "ASSIGNED_OBSERVED", 5, 5, null, null, null, null, null, readiness, "a".repeat(64));
    }
}
