package com.fdmultimedia.api.safety;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ExecutionOrigin;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.RevisionSummary;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.RollbackRequest;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalService;
import com.fdmultimedia.api.robotchanges.RobotConfigurationRevision;
import com.fdmultimedia.api.robotchanges.RobotConfigurationRevisionRepository;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotRepository;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.*;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class RollbackRecommendationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final FakeSafetyStore store = new FakeSafetyStore();
    private final AuthService auth = mock(AuthService.class);
    private final RobotConfigurationRevisionRepository revisions = mock(RobotConfigurationRevisionRepository.class);
    private final RobotRepository robots = mock(RobotRepository.class);
    private final RobotChangeProposalService canonicalRollback = mock(RobotChangeProposalService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final RollbackRecommendationService service = new RollbackRecommendationService(store, auth, revisions, robots, canonicalRollback, tx, clock);

    private final Workspace workspace = new Workspace("Test", "test");
    private final AppUser owner = new AppUser("owner@example.test", "hash", "Owner");
    private final AuthenticatedUser user = new AuthenticatedUser(owner);
    private final UUID robotId = UUID.randomUUID();
    private final UUID revisionId = UUID.randomUUID();
    private final UUID personaA = UUID.randomUUID();
    private final UUID personaB = UUID.randomUUID();
    private RecommendationRecord recommendation;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
        when(auth.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        currentPersona(personaB);
        recommendation = new RecommendationRecord(UUID.randomUUID(), workspace.getId(), robotId, revisionId, 1, UUID.randomUUID(), UUID.randomUUID(),
                Window.H72, RecommendationStatus.OPEN, Metric.TOTAL_INTERACTIONS, "TEST", personaA, "A", personaB, "B",
                ExecutionOrigin.HUMAN_APPLY, null, 10, new BigDecimal("100"), 10, new BigDecimal("1"), new BigDecimal("80"),
                new BigDecimal("-20"), new BigDecimal("-20"), "Material adverse post-change difference observed.",
                PostChangeSafetyModels.NON_CAUSAL_DISCLAIMER, NOW, null, null, null, null);
        store.recommendations.put(recommendation.id(), recommendation);
        when(revisions.maxRevision(robotId)).thenReturn(1);
    }

    private void currentPersona(UUID id) {
        Persona p = mock(Persona.class);
        when(p.getId()).thenReturn(id);
        Robot robot = mock(Robot.class);
        when(robot.getPersona()).thenReturn(p);
        when(robots.findById(robotId)).thenReturn(Optional.of(robot));
    }

    private RecommendationRecord stored() { return store.recommendations.get(recommendation.id()); }

    @Test
    void acknowledgeOnlyMarksSeenAndNeverTouchesTheRobot() {
        RecommendationRecord r = service.acknowledge(user, recommendation.id());
        assertThat(r.status()).isEqualTo(RecommendationStatus.ACKNOWLEDGED);
        assertThat(r.acknowledgedAt()).isEqualTo(NOW);
        verifyNoInteractions(canonicalRollback);
        verify(robots, never()).saveAndFlush(any());
        assertThat(service.acknowledge(user, recommendation.id()).status()).as("idempotent").isEqualTo(RecommendationStatus.ACKNOWLEDGED);
    }

    @Test
    void dismissOnlyRecordsTheHumanDecisionAndNeverTouchesTheRobot() {
        RecommendationRecord r = service.dismiss(user, recommendation.id());
        assertThat(r.status()).isEqualTo(RecommendationStatus.DISMISSED);
        assertThat(r.resolvedAt()).isEqualTo(NOW);
        verifyNoInteractions(canonicalRollback);
        verify(robots, never()).saveAndFlush(any());
        assertThatThrownBy(() -> service.acknowledge(user, recommendation.id())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RECOMMENDATION_NOT_ACTIONABLE");
    }

    @Test
    void acknowledgedRecommendationCanStillBeDismissed() {
        service.acknowledge(user, recommendation.id());
        assertThat(service.dismiss(user, recommendation.id()).status()).isEqualTo(RecommendationStatus.DISMISSED);
    }

    @Test
    void humanRollbackDelegatesToTheCanonicalServiceOnceAndLinksTheRollbackRevision() {
        UUID rollbackRevision = UUID.randomUUID();
        RevisionSummary summary = mock(RevisionSummary.class);
        when(summary.id()).thenReturn(rollbackRevision);
        when(canonicalRollback.rollback(eq(user), eq(robotId), eq(revisionId), any(RollbackRequest.class))).thenReturn(summary);

        RecommendationRecord r = service.rollback(user, recommendation.id(), "Reviewed evidence");

        assertThat(r.status()).isEqualTo(RecommendationStatus.ROLLED_BACK);
        assertThat(r.rollbackRevisionId()).isEqualTo(rollbackRevision);
        verify(canonicalRollback, times(1)).rollback(eq(user), eq(robotId), eq(revisionId), eq(new RollbackRequest("Reviewed evidence")));
        // idempotent: a repeat finds the recommendation resolved and performs no second rollback
        assertThat(service.rollback(user, recommendation.id(), null).status()).isEqualTo(RecommendationStatus.ROLLED_BACK);
        verify(canonicalRollback, times(1)).rollback(any(), any(), any(), any());
    }

    @Test
    void canonicalRollbackRejectionLeavesTheRecommendationActionable() {
        when(canonicalRollback.rollback(any(), any(), any(), any())).thenThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "ROLLBACK_PERSONA_ARCHIVED"));
        assertThatThrownBy(() -> service.rollback(user, recommendation.id(), null)).hasMessageContaining("ROLLBACK_PERSONA_ARCHIVED");
        assertThat(stored().status()).isEqualTo(RecommendationStatus.OPEN);
        assertThat(stored().rollbackRevisionId()).isNull();
    }

    @Test
    void interveningRevisionSupersedesTheRecommendationAndRollbackIsRejectedWithoutCallingTheCanonicalPath() {
        when(revisions.maxRevision(robotId)).thenReturn(2);
        assertThatThrownBy(() -> service.rollback(user, recommendation.id(), null)).hasMessageContaining("RECOMMENDATION_SUPERSEDED");
        assertThat(stored().status()).as("the SUPERSEDED transition is persisted even though the action was rejected")
                .isEqualTo(RecommendationStatus.SUPERSEDED);
        verifyNoInteractions(canonicalRollback);
    }

    @Test
    void manualPersonaChangeAlsoSupersedes() {
        currentPersona(UUID.randomUUID());
        assertThat(service.get(user, recommendation.id()).status()).isEqualTo(RecommendationStatus.SUPERSEDED);
        assertThatThrownBy(() -> service.rollback(user, recommendation.id(), null)).hasMessageContaining("RECOMMENDATION_SUPERSEDED");
        verifyNoInteractions(canonicalRollback);
    }

    @Test
    void aRollbackPerformedThroughTheCanonicalEndpointResolvesTheRecommendationAsRolledBack() {
        RobotConfigurationRevision rollbackRevision = mock(RobotConfigurationRevision.class);
        UUID id = UUID.randomUUID();
        when(rollbackRevision.getId()).thenReturn(id);
        when(revisions.findByRobotIdAndRollbackOfRevisionId(robotId, revisionId)).thenReturn(Optional.of(rollbackRevision));
        RecommendationRecord r = service.get(user, recommendation.id());
        assertThat(r.status()).isEqualTo(RecommendationStatus.ROLLED_BACK);
        assertThat(r.rollbackRevisionId()).isEqualTo(id);
    }

    @Test
    void dismissedRecommendationCannotBeRolledBackFromTheRecommendation() {
        service.dismiss(user, recommendation.id());
        assertThatThrownBy(() -> service.rollback(user, recommendation.id(), null)).hasMessageContaining("RECOMMENDATION_NOT_ACTIONABLE");
        verifyNoInteractions(canonicalRollback);
    }

    @Test
    void recommendationsAreWorkspaceIsolated() {
        Workspace other = new Workspace("Other", "other");
        AppUser stranger = new AppUser("s@example.test", "h", "S");
        AuthenticatedUser outsider = new AuthenticatedUser(stranger);
        when(auth.currentMembershipFor(outsider)).thenReturn(new WorkspaceMembership(other, stranger, WorkspaceRole.OWNER));
        assertThatThrownBy(() -> service.get(outsider, recommendation.id())).hasMessageContaining("404");
        assertThatThrownBy(() -> service.acknowledge(outsider, recommendation.id())).hasMessageContaining("404");
        assertThatThrownBy(() -> service.dismiss(outsider, recommendation.id())).hasMessageContaining("404");
        assertThatThrownBy(() -> service.rollback(outsider, recommendation.id(), null)).hasMessageContaining("404");
        assertThat(service.list(outsider, null, null, 20)).isEmpty();
        assertThat(stored().status()).isEqualTo(RecommendationStatus.OPEN);
    }

    @Test
    void listFiltersByStatusAndRejectsUnknownStatus() {
        assertThat(service.list(user, "OPEN", null, 20)).hasSize(1);
        assertThat(service.list(user, "DISMISSED", null, 20)).isEmpty();
        assertThatThrownBy(() -> service.list(user, "NOPE", null, 20)).hasMessageContaining("UNSUPPORTED_STATUS");
    }
}
