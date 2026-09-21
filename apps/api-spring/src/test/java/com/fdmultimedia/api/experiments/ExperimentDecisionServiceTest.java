package com.fdmultimedia.api.experiments;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ExperimentDecisionServiceTest {
    private final AuthService auth = mock(AuthService.class);
    private final ExperimentRepository experiments = mock(ExperimentRepository.class);
    private final ExperimentDecisionReadinessService readiness = mock(ExperimentDecisionReadinessService.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AuthenticatedUser user = mock(AuthenticatedUser.class);
    private final Workspace workspace = mock(Workspace.class);
    private final Experiment experiment = mock(Experiment.class);
    private final UUID id = UUID.randomUUID();
    private final ExperimentDecisionService service = new ExperimentDecisionService(auth, experiments, readiness, jdbc, Clock.systemUTC());

    @BeforeEach
    void setup() {
        WorkspaceMembership membership = mock(WorkspaceMembership.class);
        when(auth.currentMembershipFor(user)).thenReturn(membership);
        when(membership.getWorkspace()).thenReturn(workspace);
        when(workspace.getId()).thenReturn(UUID.randomUUID());
        when(experiments.findByWorkspaceAndId(workspace, id)).thenReturn(Optional.of(experiment));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
    }

    private ExperimentDecisionRequest request(String decision, String variant, String rationale) {
        return new ExperimentDecisionRequest(decision, variant, AnalysisPopulation.ASSIGNED_OBSERVED, rationale, UUID.randomUUID());
    }

    private void rejected(ExperimentDecisionRequest request, String code) {
        assertThatThrownBy(() -> service.create(user, id, request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining(code);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void foreignExperimentIsInvisibleWithoutReadingEvidenceOrWritingDecision() {
        when(experiments.findByWorkspaceAndId(workspace, id)).thenReturn(Optional.empty());
        rejected(request("INCONCLUSIVE", null, "Need more outcomes"), "404");
        verify(readiness, never()).read(any(), any());
    }

    @Test
    void draftCannotRecordDecision() {
        when(experiment.getStatus()).thenReturn(ExperimentStatus.DRAFT);
        rejected(request("INCONCLUSIVE", null, "Need more outcomes"), "DRAFT_EXPERIMENT_DECISION_UNAVAILABLE");
    }

    @Test
    void cancelledExperimentCannotSelectEitherVariant() {
        when(experiment.getStatus()).thenReturn(ExperimentStatus.CANCELLED);
        rejected(request("SELECT_VARIANT_A", "A", "A review"), "CANCELLED_EXPERIMENT_VARIANT_SELECTION_UNAVAILABLE");
    }

    @Test
    void variantMustMatchDecisionAndNeutralDecisionsCannotSelectOne() {
        rejected(request("SELECT_VARIANT_B", "A", "Review"), "Selected variant");
        rejected(request("SELECT_VARIANT_A", null, "Review"), "Selected variant");
        rejected(request("INCONCLUSIVE", "B", "Review"), "Selected variant");
    }

    @Test
    void rationaleIsRequiredAndBounded() {
        rejected(request("INCONCLUSIVE", null, "  "), "Rationale");
        rejected(request("INCONCLUSIVE", null, "x".repeat(2001)), "Rationale");
    }

    @Test
    void historyAndDetailAreWorkspaceScoped() {
        when(experiments.findByWorkspaceAndId(workspace, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.history(user, id)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> service.detail(user, id, UUID.randomUUID())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verify(jdbc, never()).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }
}
