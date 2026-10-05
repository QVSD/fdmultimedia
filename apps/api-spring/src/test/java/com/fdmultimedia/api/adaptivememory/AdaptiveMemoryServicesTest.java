package com.fdmultimedia.api.adaptivememory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.*;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotRepository;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class AdaptiveMemoryServicesTest {
    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    private final Clock clock = Clock.fixed(T0.plus(Duration.ofDays(10)), ZoneOffset.UTC);
    private final FakeAdaptiveMemoryStore store = new FakeAdaptiveMemoryStore();
    private final AdaptiveMemoryProjectionService projection = new AdaptiveMemoryProjectionService(store, clock);
    private final AuthService auth = mock(AuthService.class);
    private final RobotRepository robots = mock(RobotRepository.class);
    private final AdaptiveMemoryService service = new AdaptiveMemoryService(store, projection, auth, robots, clock);

    private final Workspace workspace = new Workspace("Test", "test");
    private final AppUser owner = new AppUser("owner@example.test", "hash", "Owner");
    private final AuthenticatedUser user = new AuthenticatedUser(owner);
    private final UUID robotId = UUID.randomUUID();
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    private int seq;

    private Fact fact(EventType type, SourceType source, Instant at, UUID from, UUID to) {
        UUID id = new UUID(7, ++seq);
        return new Fact(type, source, id, at, workspace.getId(), robotId, from, to, id, null, id, id, "HUMAN_APPLY", "H72", null);
    }

    @BeforeEach
    void setUp() {
        store.robotWorkspaces.put(robotId, workspace.getId());
        store.currentPersonas.put(robotId, a);
        store.names.put(a, "A"); store.names.put(b, "B"); store.names.put(c, "C");
        Robot robot = mock(Robot.class);
        Persona current = mock(Persona.class);
        when(current.getId()).thenReturn(a);
        when(robot.getPersona()).thenReturn(current);
        when(robots.findByWorkspaceAndId(workspace, robotId)).thenReturn(Optional.of(robot));
        when(auth.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        store.history.addAll(List.of(
                fact(EventType.PROPOSAL_CREATED, SourceType.OPTIMIZATION_PROPOSAL, T0, a, b),
                fact(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0.plusSeconds(60), a, b),
                fact(EventType.SAFETY_REGRESSION, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plus(Duration.ofDays(4)), a, b),
                fact(EventType.CHANGE_ROLLED_BACK, SourceType.ROBOT_CONFIGURATION_REVISION, T0.plus(Duration.ofDays(5)), a, b),
                fact(EventType.PROPOSAL_CREATED, SourceType.OPTIMIZATION_PROPOSAL, T0.plus(Duration.ofDays(6)), a, c)));
    }

    // ---- projection ----

    @Test
    void backfillProjectsExistingHistoryIntoEventsAndRows() {
        assertThat(projection.reconcileRobot(robotId)).isPositive();
        assertThat(store.events).hasSize(5);
        Memory ab = store.rows.get(new AdaptiveMemoryProjector.Key(robotId, a, b));
        assertThat(ab.latestOutcome()).isEqualTo(Outcome.ROLLED_BACK);
        assertThat(ab.proposalCount()).isEqualTo(1);
        assertThat(ab.applyCount()).isEqualTo(1);
        assertThat(ab.rollbackCount()).isEqualTo(1);
        assertThat(ab.regressionCount()).isEqualTo(1);
        assertThat(store.rows.get(new AdaptiveMemoryProjector.Key(robotId, a, c)).latestOutcome()).isEqualTo(Outcome.PROPOSED);
    }

    @Test
    void reconcileIsIdempotentAndRestartSafeWithNoDuplicateEvents() {
        projection.reconcileRobot(robotId);
        Map<AdaptiveMemoryProjector.Key, Memory> snapshot = new LinkedHashMap<>(store.rows);
        int writes = store.upsertWrites;
        assertThat(projection.reconcileRobot(robotId)).isZero();
        assertThat(projection.reconcileRobot(robotId)).isZero();
        assertThat(store.events).hasSize(5);
        assertThat(store.rows).isEqualTo(snapshot);
        assertThat(store.upsertWrites).isEqualTo(writes);
    }

    @Test
    void duplicateSourceFactsProjectExactlyOnceEvenWhenConcurrentCallersRepeatThem() {
        List<Fact> duplicated = new ArrayList<>(store.history);
        duplicated.addAll(store.history); // two reconcilers collected the same facts
        assertThat(store.insertMissingEvents(duplicated, T0)).isEqualTo(5);
        assertThat(store.events).hasSize(5);
        projection.reconcileRobot(robotId);
        assertThat(store.rows.get(new AdaptiveMemoryProjector.Key(robotId, a, b)).proposalCount()).isEqualTo(1);
    }

    @Test
    void projectionIsRebuildableFromHistoryAfterTheRowsAreDropped() {
        projection.reconcileRobot(robotId);
        Map<AdaptiveMemoryProjector.Key, Memory> original = new LinkedHashMap<>(store.rows);
        store.rows.clear();
        store.events.clear();
        projection.reconcileRobot(robotId);
        assertThat(store.rows).isEqualTo(original);
    }

    @Test
    void lateSafetyEvidenceArrivesAfterApplyAndOnlyUpdatesThatTransition() {
        List<Fact> early = new ArrayList<>(store.history.subList(0, 2)); // proposed, applied
        store.history.clear(); store.history.addAll(early);
        projection.reconcileRobot(robotId);
        assertThat(store.rows.get(new AdaptiveMemoryProjector.Key(robotId, a, b)).latestOutcome()).isEqualTo(Outcome.APPLIED);
        store.history.add(fact(EventType.SAFETY_STABLE, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plus(Duration.ofDays(4)), a, b));
        assertThat(projection.reconcileRobot(robotId)).isEqualTo(2); // one new event, one changed row
        assertThat(store.rows.get(new AdaptiveMemoryProjector.Key(robotId, a, b)).latestOutcome()).isEqualTo(Outcome.OBSERVED_STABLE);
    }

    @Test
    void unknownRobotProjectsNothing() {
        assertThat(projection.reconcileRobot(UUID.randomUUID())).isZero();
        assertThat(store.events).isEmpty();
    }

    // ---- read side / screening ----

    @Test
    void screenFiltersRolledBackTransitionAndAllowsUnrelatedCandidateWithoutReordering() {
        AdaptiveMemoryService.Screen screen = service.screen(robotId, a);
        Decision rolledBack = screen.decide(b);
        assertThat(rolledBack.eligible()).isFalse();
        assertThat(rolledBack.reasons()).contains(SuppressionReason.ROLLED_BACK, SuppressionReason.OBSERVED_REGRESSION);
        assertThat(rolledBack.latestOutcome()).isEqualTo(Outcome.ROLLED_BACK);
        assertThat(rolledBack.engineVersion()).isEqualTo("ADAPTIVE_MEMORY_SCREENING_V1");
        // C was proposed 4 days ago -> temporarily suppressed too; a never-seen candidate is eligible
        assertThat(screen.decide(c).reasons()).containsExactly(SuppressionReason.RECENTLY_PROPOSED);
        assertThat(screen.decide(UUID.randomUUID()).eligible()).isTrue();
        assertThat(screen.decide(a).reasons()).containsExactly(SuppressionReason.CURRENTLY_ACTIVE);
    }

    @Test
    void memoryListingIsReadOnlyBoundedAndNeutral() {
        RobotMemory memory = service.memory(user, robotId);
        assertThat(memory.engineVersion()).isEqualTo("ADAPTIVE_MEMORY_V1");
        assertThat(memory.transitions()).extracting(MemoryView::toPersonaName).containsExactlyInAnyOrder("B", "C");
        MemoryView ab = memory.transitions().stream().filter(v -> "B".equals(v.toPersonaName())).findFirst().orElseThrow();
        assertThat(ab.suppressed()).isTrue();
        assertThat(ab.rollbackCount()).isEqualTo(1);
        assertThat(ab.suppressionUntil()).isEqualTo(T0.plus(Duration.ofDays(95)));
        assertThat(memory.transitions()).as("ordered by last activity, never by a quality measure")
                .extracting(MemoryView::toPersonaName).containsExactly("C", "B");
    }

    @Test
    void manualDecisionIsAWarningNotABlockAndReviewVariantResolvesTheRobot() {
        DecisionView view = service.decision(user, robotId, b);
        assertThat(view.warning()).isTrue();
        assertThat(view.decision().reasons()).contains(SuppressionReason.ROLLED_BACK);
        assertThat(view.fromPersonaName()).isEqualTo("A");
        assertThat(view.toPersonaName()).isEqualTo("B");
        UUID review = UUID.randomUUID();
        store.reviewRobots.put(review, robotId);
        assertThat(service.decisionForReview(user, review, a, b).decision().eligible()).isFalse();
        assertThat(service.decisionForReview(user, review, a, UUID.randomUUID()).warning()).isFalse();
    }

    @Test
    void workspaceIsolation() {
        Workspace other = new Workspace("Other", "other");
        AppUser stranger = new AppUser("s@example.test", "h", "S");
        AuthenticatedUser outsider = new AuthenticatedUser(stranger);
        when(auth.currentMembershipFor(outsider)).thenReturn(new WorkspaceMembership(other, stranger, WorkspaceRole.OWNER));
        when(robots.findByWorkspaceAndId(other, robotId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.memory(outsider, robotId)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.decision(outsider, robotId, b)).isInstanceOf(ResponseStatusException.class);
        UUID review = UUID.randomUUID();
        store.reviewRobots.put(review, robotId);
        assertThatThrownBy(() -> service.decisionForReview(outsider, review, a, b)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void reconcilerRepairsABoundedBatchAndSurvivesAFailingRobot() {
        AdaptiveMemoryStore stub = mock(AdaptiveMemoryStore.class);
        UUID r1 = UUID.randomUUID(), r2 = UUID.randomUUID();
        when(stub.robotsNeedingReconciliation(any(), org.mockito.ArgumentMatchers.eq(AdaptiveMemoryReconciler.BATCH_LIMIT))).thenReturn(List.of(r1, r2));
        AdaptiveMemoryProjectionService projectionMock = mock(AdaptiveMemoryProjectionService.class);
        when(projectionMock.reconcileRobot(r1)).thenThrow(new IllegalStateException("boom"));
        when(projectionMock.reconcileRobot(r2)).thenReturn(3);
        new AdaptiveMemoryReconciler(stub, projectionMock, clock,
                new com.fdmultimedia.api.shared.operations.SchedulerOperationTracker(clock)).reconcile();
        verify(projectionMock).reconcileRobot(r1);
        verify(projectionMock).reconcileRobot(r2);
        assertThat(AdaptiveMemoryReconciler.BATCH_LIMIT).isEqualTo(100);
    }
}
