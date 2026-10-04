package com.fdmultimedia.api.robotchanges;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fdmultimedia.api.analytics.CampaignPerformanceService;
import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import com.fdmultimedia.api.experiments.ExperimentAnalysisService;
import com.fdmultimedia.api.experiments.ExperimentRepository;
import com.fdmultimedia.api.experiments.ExperimentVariantRepository;
import com.fdmultimedia.api.optimization.AutonomousProposalService;
import com.fdmultimedia.api.optimization.OptimizationProposalRepository;
import com.fdmultimedia.api.optimization.OptimizationProposalService;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.*;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.PolicySummary;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.Trigger;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ChangeType;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ExecutionOrigin;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.Status;
import com.fdmultimedia.api.robots.*;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

class AdaptiveExecutionTest {
    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final Instant T0 = Instant.parse("2026-10-05T00:00:00Z");
    private final MutableClock clock = new MutableClock(T0);

    private final AuthService auth = mock(AuthService.class);
    private final RobotChangeProposalRepository proposals = mock(RobotChangeProposalRepository.class);
    private final RobotConfigurationRevisionRepository revisions = mock(RobotConfigurationRevisionRepository.class);
    private final RobotRepository robots = mock(RobotRepository.class);
    private final PersonaRepository personas = mock(PersonaRepository.class);
    private final AdaptiveGuardrailService guardrails = mock(AdaptiveGuardrailService.class);
    private final RobotAdaptiveExecutionAuthorizationRepository authorizations = mock(RobotAdaptiveExecutionAuthorizationRepository.class);
    private final AdaptiveExecutionAttemptRepository attempts = mock(AdaptiveExecutionAttemptRepository.class);
    private final RobotAdaptivePolicyService policies = mock(RobotAdaptivePolicyService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final RobotChangeProposalService proposalService = new RobotChangeProposalService(auth, proposals, revisions,
            mock(OptimizationProposalRepository.class), mock(ExperimentRepository.class), mock(ExperimentVariantRepository.class),
            mock(ExperimentAnalysisService.class), robots, personas, guardrails, authorizations, attempts, clock);
    private final AdaptiveExecutionExecutor executor = new AdaptiveExecutionExecutor(authorizations, attempts, proposals, robots,
            personas, guardrails, proposalService, clock);
    private final AdaptiveExecutionService service = new AdaptiveExecutionService(auth, authorizations, attempts, proposals, robots,
            policies, executor, events, clock);

    private final Workspace workspace = new Workspace("Test", "test");
    private final AppUser owner = new AppUser("owner@example.test", "hash", "Owner");
    private final AuthenticatedUser principal = new AuthenticatedUser(owner);
    private final Persona current = persona("Current");
    private final Persona target = persona("Target");
    private Robot robot;
    private RobotChangeProposal proposal;
    private AdaptiveGuardrailEvaluation evaluation;
    private final List<RobotAdaptiveExecutionAuthorization> storedAuthorizations = new ArrayList<>();
    private final List<RobotConfigurationRevision> storedRevisions = new ArrayList<>();
    private final List<AdaptiveExecutionAttempt> storedAttempts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(auth.currentMembershipFor(principal)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        robot = new Robot(workspace, "Robot", "d", RobotAutonomyMode.REVIEW_REQUIRED, RobotSourcePolicy.EXISTING_ASSET, null, null,
                null, null, RobotCadenceType.MANUAL_ONLY, null, null, 5, RobotAiPolicy.GENERATE_FOR_REVIEW, current,
                SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL, null, owner, T0);
        String expected = RobotChangeProposalService.robotConfigFingerprint(robot.getId(), current.getId());
        proposal = new RobotChangeProposal(workspace, UUID.randomUUID(), UUID.randomUUID(), robot.getId(), "Robot", current.getId(),
                "Current", "cfp", target.getId(), "Target", "tfp", expected, DashboardQuery.Metric.VIEWS, DashboardQuery.Window.H72,
                10, 10, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.TEN, false, new BigDecimal("0.01"), BigDecimal.ONE, "l", "r", "pfp", owner, T0);
        proposal.approve(T0);
        evaluation = eval(List.of());
        when(proposals.findByWorkspaceAndId(workspace, proposal.getId())).thenReturn(Optional.of(proposal));
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, proposal.getId())).thenReturn(Optional.of(proposal));
        when(robots.findByWorkspaceAndId(workspace, robot.getId())).thenReturn(Optional.of(robot));
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        when(personas.findByWorkspaceAndId(workspace, target.getId())).thenAnswer(i -> Optional.of(target));
        when(personas.findByWorkspaceAndId(workspace, current.getId())).thenAnswer(i -> Optional.of(current));
        when(guardrails.evaluate(any(), any(), any(), any())).thenAnswer(i -> evaluation);
        when(guardrails.evaluateAndPersist(any(), any(), any(), any())).thenAnswer(i -> evaluation);
        when(policies.effective(workspace, robot.getId())).thenReturn(new PolicySummary(robot.getId(), 3, true, true, 2, 30, 72, true,
                true, true, T0));
        when(authorizations.saveAndFlush(any())).thenAnswer(i -> {
            RobotAdaptiveExecutionAuthorization a = i.getArgument(0);
            if (!storedAuthorizations.contains(a)) storedAuthorizations.add(a);
            return a;
        });
        when(authorizations.countActiveForProposal(any())).thenAnswer(i -> storedAuthorizations.stream().filter(RobotAdaptiveExecutionAuthorization::isActive).count());
        when(authorizations.findByIdForUpdate(any())).thenAnswer(i -> storedAuthorizations.stream().filter(a -> a.getId().equals(i.getArgument(0))).findFirst());
        when(authorizations.findByWorkspaceAndId(eq(workspace), any())).thenAnswer(i -> storedAuthorizations.stream().filter(a -> a.getId().equals(i.getArgument(1))).findFirst());
        when(authorizations.findByWorkspaceAndIdForUpdate(eq(workspace), any())).thenAnswer(i -> storedAuthorizations.stream().filter(a -> a.getId().equals(i.getArgument(1))).findFirst());
        when(authorizations.findActiveForProposalForUpdate(eq(workspace), any())).thenAnswer(i -> storedAuthorizations.stream().filter(RobotAdaptiveExecutionAuthorization::isActive).toList());
        when(attempts.saveAndFlush(any())).thenAnswer(i -> { storedAttempts.add(i.getArgument(0)); return i.getArgument(0); });
        when(attempts.findTopByAuthorizationIdOrderByAttemptedAtDesc(any())).thenAnswer(i -> storedAttempts.isEmpty() ? Optional.empty() : Optional.of(storedAttempts.get(storedAttempts.size() - 1)));
        when(robots.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(proposals.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(revisions.saveAndFlush(any())).thenAnswer(i -> { storedRevisions.add(i.getArgument(0)); return i.getArgument(0); });
        when(revisions.maxRevision(robot.getId())).thenAnswer(i -> storedRevisions.size());
        when(revisions.findByWorkspaceAndRobotIdAndId(eq(workspace), eq(robot.getId()), any())).thenAnswer(i -> storedRevisions.stream().filter(r -> r.getId().equals(i.getArgument(2))).findFirst());
        when(revisions.findByRobotIdAndRollbackOfRevisionId(any(), any())).thenReturn(Optional.empty());
    }

    // ---- creation ----

    @Test void creationRequiresAnApprovedProposal() {
        RobotChangeProposal pending = pendingProposal();
        for (Status status : List.of(Status.READY_FOR_REVIEW, Status.REJECTED, Status.STALE)) {
            RobotChangeProposal p = pendingProposal();
            when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
            switch (status) { case REJECTED -> p.reject(T0); case STALE -> p.markStale(); default -> {} }
            assertThatThrownBy(() -> service.create(principal, p.getId(), null)).isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("PROPOSAL_NOT_APPROVED");
        }
        assertThat(storedAuthorizations).isEmpty();
        assertThat(pending.getStatus()).isEqualTo(Status.READY_FOR_REVIEW);
    }

    @Test void creationDerivesExactScopeFromTheProposalWithSingleExecutionAndDefaultExpiry() {
        AuthorizationSummary s = service.create(principal, proposal.getId(), null);
        assertThat(s.robotId()).isEqualTo(robot.getId());
        assertThat(s.fromPersonaId()).isEqualTo(current.getId());
        assertThat(s.toPersonaId()).isEqualTo(target.getId());
        assertThat(s.sourceExperimentId()).isEqualTo(proposal.getSourceExperimentId());
        assertThat(s.factor()).isEqualTo("PERSONA");
        assertThat(s.maxExecutions()).isEqualTo(1);
        assertThat(s.status()).isEqualTo(AuthorizationStatus.ACTIVE);
        assertThat(s.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(AdaptiveExecutionService.DEFAULT_DURATION_HOURS)));
        assertThat(s.policyRevision()).isEqualTo(3);
        assertThat(s.executionEngineVersion()).isEqualTo("PREAUTHORIZED_EXECUTION_V1");
        verify(events).publishEvent(any(AdaptiveExecutionAuthorizationCreatedEvent.class));
    }

    @Test void expiryBoundsAreServerEnforced() {
        for (int bad : new int[] {0, -1, 721, 100000})
            assertThatThrownBy(() -> service.create(principal, proposal.getId(), new CreateRequest(bad)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("DURATION_HOURS_OUT_OF_BOUNDS");
        assertThat(service.create(principal, proposal.getId(), new CreateRequest(1)).expiresAt()).isEqualTo(T0.plus(Duration.ofHours(1)));
        storedAuthorizations.clear();
        assertThat(service.create(principal, proposal.getId(), new CreateRequest(720)).expiresAt()).isEqualTo(T0.plus(Duration.ofDays(30)));
    }

    @Test void onlyOneActiveAuthorizationPerProposalAndRobotDivergenceBlocksCreation() {
        service.create(principal, proposal.getId(), null);
        assertThatThrownBy(() -> service.create(principal, proposal.getId(), null)).hasMessageContaining("AUTHORIZATION_ALREADY_ACTIVE");
        storedAuthorizations.clear();
        robot.applyPersona(persona("Elsewhere"), T0.plusSeconds(1));
        assertThatThrownBy(() -> service.create(principal, proposal.getId(), null)).hasMessageContaining("ROBOT_CONFIGURATION_CHANGED");
    }

    @Test void authorizationScopeIsImmutable() {
        assertThat(Arrays.stream(RobotAdaptiveExecutionAuthorization.class.getDeclaredMethods()).map(m -> m.getName()))
                .noneMatch(n -> n.startsWith("set"));
        assertThat(Arrays.stream(RobotAdaptiveExecutionAuthorization.class.getDeclaredFields())
                .filter(f -> List.of("proposalId", "robotId", "toPersonaId", "fromPersonaId", "sourceExperimentId", "expiresAt", "maxExecutions").contains(f.getName()))
                .allMatch(f -> f.getAnnotation(jakarta.persistence.Column.class).updatable() == false)).isTrue();
    }

    // ---- execution ----

    @Test void eligibleAuthorizationAppliesExactlyOnceWithFullProvenanceAndIsConsumed() {
        RobotAdaptiveExecutionAuthorization a = authorization();
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isEqualTo(AttemptResult.APPLIED);
        assertThat(robot.getPersona().getId()).isEqualTo(target.getId());
        assertThat(storedRevisions).hasSize(1);
        RobotConfigurationRevision r = storedRevisions.get(0);
        assertThat(r.getExecutionOrigin()).isEqualTo(ExecutionOrigin.PREAUTHORIZED_AUTO_APPLY);
        assertThat(r.getExecutionAuthorizationId()).isEqualTo(a.getId());
        assertThat(r.getExecutionEngineVersion()).isEqualTo("PREAUTHORIZED_EXECUTION_V1");
        assertThat(r.getGuardrailEvaluationId()).isEqualTo(evaluation.getId());
        assertThat(r.getAdaptivePolicyRevision()).isEqualTo(evaluation.getPolicyRevision());
        assertThat(r.getGuardrailEngineVersion()).isEqualTo("ADAPTIVE_GUARDRAILS_V1");
        assertThat(a.getStatus()).isEqualTo(AuthorizationStatus.CONSUMED);
        assertThat(a.getConsumedRevisionId()).isEqualTo(r.getId());
        assertThat(proposal.getStatus()).isEqualTo(Status.APPLIED);
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isNull();
        assertThat(storedRevisions).hasSize(1);
        verify(guardrails).evaluateAndPersist(any(), any(), any(), eq(Trigger.APPLY));
    }

    @Test void temporaryGuardrailBlockNeverConsumesAndAnIdenticalBlockedStateIsNotAuditedRepeatedly() {
        evaluation = eval(List.of(RobotAdaptivePolicyModels.Reason.COOLDOWN_ACTIVE));
        RobotAdaptiveExecutionAuthorization a = authorization();
        for (int i = 0; i < 5; i++) assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isEqualTo(AttemptResult.BLOCKED);
        assertThat(a.getStatus()).isEqualTo(AuthorizationStatus.ACTIVE);
        assertThat(robot.getPersona().getId()).isEqualTo(current.getId());
        assertThat(storedRevisions).isEmpty();
        assertThat(storedAttempts).hasSize(1);
        assertThat(storedAttempts.get(0).getReasonCodes()).contains("GUARDRAIL_BLOCKED").contains("COOLDOWN_ACTIVE");
        verify(guardrails, never()).evaluateAndPersist(any(), any(), any(), any());
    }

    @Test void blockedAuthorizationBecomesEligibleAfterTheLatestPolicyIsRelaxedWithoutANewAuthorization() {
        evaluation = eval(List.of(RobotAdaptivePolicyModels.Reason.CHANGE_BUDGET_EXHAUSTED));
        RobotAdaptiveExecutionAuthorization a = authorization();
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isEqualTo(AttemptResult.BLOCKED);
        evaluation = eval(List.of());
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isEqualTo(AttemptResult.APPLIED);
    }

    @Test void policyDisabledPausesWithoutConsumingAndKeepsStableReasonOrdering() {
        evaluation = eval(List.of(RobotAdaptivePolicyModels.Reason.POLICY_DISABLED, RobotAdaptivePolicyModels.Reason.COOLDOWN_ACTIVE));
        RobotAdaptiveExecutionAuthorization a = authorization();
        ExecutionEligibility e = service.eligibility(principal, a.getId());
        assertThat(e.eligibleNow()).isFalse();
        assertThat(e.reasons()).containsExactly(Reason.ADAPTIVE_POLICY_DISABLED, Reason.GUARDRAIL_BLOCKED);
        assertThat(e.guardrailReasons()).contains("POLICY_DISABLED", "COOLDOWN_ACTIVE");
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isEqualTo(AttemptResult.BLOCKED);
        assertThat(a.getStatus()).isEqualTo(AuthorizationStatus.ACTIVE);
    }

    @Test void dryRunHasNoPersistenceSideEffects() {
        RobotAdaptiveExecutionAuthorization a = authorization();
        clearInvocations(authorizations, attempts, robots, revisions, proposals);
        ExecutionEligibility e = service.eligibility(principal, a.getId());
        assertThat(e.eligibleNow()).isTrue();
        assertThat(e.reasons()).containsExactly(Reason.ELIGIBLE);
        verify(authorizations, never()).saveAndFlush(any());
        verify(attempts, never()).saveAndFlush(any());
        verify(robots, never()).saveAndFlush(any());
        verify(revisions, never()).saveAndFlush(any());
        verify(proposals, never()).saveAndFlush(any());
    }

    @Test void expirationUsesServerClockAndIsEligibleOnlyBeforeExpiresAt() {
        RobotAdaptiveExecutionAuthorization a = authorization();
        clock.now = a.getExpiresAt().minusMillis(1);
        assertThat(service.eligibility(principal, a.getId()).eligibleNow()).isTrue();
        clock.now = a.getExpiresAt();
        assertThat(service.eligibility(principal, a.getId()).reasons()).contains(Reason.AUTHORIZATION_EXPIRED);
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isEqualTo(AttemptResult.EXPIRED);
        assertThat(a.getStatus()).isEqualTo(AuthorizationStatus.EXPIRED);
        assertThat(storedRevisions).isEmpty();
        assertThat(robot.getPersona().getId()).isEqualTo(current.getId());
        clock.now = T0.plus(Duration.ofDays(60));
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isNull();
    }

    @Test void revokedAuthorizationNeverExecutesAndRevokeIsIdempotentButCannotUndoConsumption() {
        RobotAdaptiveExecutionAuthorization a = authorization();
        assertThat(service.revoke(principal, a.getId()).status()).isEqualTo(AuthorizationStatus.REVOKED);
        assertThat(service.revoke(principal, a.getId()).status()).isEqualTo(AuthorizationStatus.REVOKED);
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isNull();
        assertThat(storedRevisions).isEmpty();
        RobotAdaptiveExecutionAuthorization consumed = authorizationFor(proposal);
        assertThat(executor.attempt(consumed.getId(), AttemptTrigger.RECONCILIATION)).isEqualTo(AttemptResult.APPLIED);
        assertThatThrownBy(() -> service.revoke(principal, consumed.getId())).hasMessageContaining("AUTHORIZATION_NOT_ACTIVE");
    }

    @Test void archivedTargetPersonaInvalidatesAndMarksTheProposalStaleWithoutMutation() {
        RobotAdaptiveExecutionAuthorization a = authorization();
        target.archive(T0.plusSeconds(5));
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isEqualTo(AttemptResult.INVALIDATED);
        assertThat(a.getStatus()).isEqualTo(AuthorizationStatus.INVALIDATED);
        assertThat(a.getTerminalReason()).isEqualTo(TerminalReason.TARGET_PERSONA_INACTIVE);
        assertThat(proposal.getStatus()).isEqualTo(Status.STALE);
        assertThat(robot.getPersona().getId()).isEqualTo(current.getId());
        assertThat(storedRevisions).isEmpty();
    }

    @Test void divergedRobotInvalidatesWithoutOverwritingANewerConfiguration() {
        RobotAdaptiveExecutionAuthorization a = authorization();
        Persona manual = persona("Manual");
        robot.applyPersona(manual, T0.plusSeconds(5));
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isEqualTo(AttemptResult.INVALIDATED);
        assertThat(a.getTerminalReason()).isEqualTo(TerminalReason.ROBOT_CONFIGURATION_CHANGED);
        assertThat(proposal.getStatus()).isEqualTo(Status.STALE);
        assertThat(robot.getPersona().getId()).isEqualTo(manual.getId());
        assertThat(storedRevisions).isEmpty();
    }

    @Test void terminalProposalsInvalidateTheAuthorization() {
        RobotAdaptiveExecutionAuthorization a = authorization();
        proposal.markStale();
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isEqualTo(AttemptResult.INVALIDATED);
        assertThat(a.getTerminalReason()).isEqualTo(TerminalReason.PROPOSAL_STALE);
    }

    @Test void manualApplyFirstTerminalizesTheAuthorizationAndNoAutomaticRevisionFollows() {
        RobotAdaptiveExecutionAuthorization a = authorization();
        assertThat(proposalService.apply(principal, proposal.getId()).status()).isEqualTo(Status.APPLIED);
        assertThat(a.getStatus()).isEqualTo(AuthorizationStatus.INVALIDATED);
        assertThat(a.getTerminalReason()).isEqualTo(TerminalReason.APPLIED_MANUALLY);
        assertThat(storedRevisions).hasSize(1);
        assertThat(storedRevisions.get(0).getExecutionOrigin()).isEqualTo(ExecutionOrigin.HUMAN_APPLY);
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isNull();
        assertThat(storedRevisions).hasSize(1);
    }

    @Test void humanApplyAfterAutomaticApplyIsIdempotentAndCreatesNoSecondRevision() {
        RobotAdaptiveExecutionAuthorization a = authorization();
        executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION);
        assertThat(proposalService.apply(principal, proposal.getId()).status()).isEqualTo(Status.APPLIED);
        assertThat(storedRevisions).hasSize(1);
        assertThat(a.getStatus()).isEqualTo(AuthorizationStatus.CONSUMED);
    }

    @Test void humanRollbackAfterAutomaticApplyWorksAndTheConsumedAuthorizationNeverReapplies() {
        RobotAdaptiveExecutionAuthorization a = authorization();
        executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION);
        UUID revisionId = storedRevisions.get(0).getId();
        var rollback = proposalService.rollback(principal, robot.getId(), revisionId, new RobotChangeProposalModels.RollbackRequest("revert"));
        assertThat(rollback.changeType()).isEqualTo(ChangeType.ROLLBACK);
        assertThat(rollback.executionOrigin()).isEqualTo(ExecutionOrigin.HUMAN_ROLLBACK);
        assertThat(robot.getPersona().getId()).isEqualTo(current.getId());
        assertThat(proposal.getStatus()).isEqualTo(Status.ROLLED_BACK);
        assertThat(executor.attempt(a.getId(), AttemptTrigger.RECONCILIATION)).isNull();
        assertThat(storedRevisions).hasSize(2);
        assertThat(a.getStatus()).isEqualTo(AuthorizationStatus.CONSUMED);
    }

    // ---- architecture ----

    @Test void executorHasNoApprovalCreationRollbackExperimentOrAiPath() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/fdmultimedia/api/robotchanges/AdaptiveExecutionExecutor.java"));
        assertThat(source).doesNotContain(".approve(").doesNotContain(".reject(").doesNotContain(".rollback(")
                .doesNotContain("OptimizationProposal").doesNotContain("ExperimentService").doesNotContain("AiProvider")
                .doesNotContain("RobotService").doesNotContain(".create(");
        for (Class<?> c : List.of(AdaptiveExecutionExecutor.class, AdaptiveExecutionService.class, AdaptiveExecutionReconciler.class))
            assertThat(Arrays.stream(c.getDeclaredFields()).map(f -> f.getType().getSimpleName()))
                    .noneMatch(n -> n.contains("AiProvider") || n.contains("Ollama") || n.contains("OptimizationProposal")
                            || n.equals("ExperimentService") || n.equals("AutonomousProposalService"));
        String applyCore = Files.readString(Path.of("src/main/java/com/fdmultimedia/api/robotchanges/RobotChangeProposalService.java"));
        assertThat(applyCore.split("applyPersona\\(").length - 1).isEqualTo(2); // canonical apply + human rollback only
    }

    @Test void earlierPhasesNeverDependOnPhase17L() {
        for (Class<?> c : List.of(CampaignPerformanceService.class, OptimizationProposalService.class, AutonomousProposalService.class,
                AdaptiveGuardrailService.class, RobotAdaptivePolicyService.class))
            assertThat(Arrays.stream(c.getDeclaredFields()).map(f -> f.getType().getSimpleName()))
                    .noneMatch(n -> n.contains("AdaptiveExecution") || n.contains("ExecutionAuthorization"));
    }

    @Test void controllerIsHumanOnlyAndSecurityMapsItToTheUserRole() throws Exception {
        assertThat(Arrays.stream(AdaptiveExecutionController.class.getDeclaredMethods()).flatMap(m -> Arrays.stream(m.getParameterTypes()))
                .map(Class::getSimpleName)).noneMatch(n -> n.contains("Worker"));
        String security = Files.readString(Path.of("src/main/java/com/fdmultimedia/api/auth/security/SecurityConfig.java"));
        assertThat(security).contains("\"/api/adaptive-execution-authorizations/**\").hasRole(\"USER\")")
                .contains("\"/api/robot-change-proposals/**\").hasRole(\"USER\")");
    }

    @Test void crossWorkspaceAuthorizationsAreInvisible() {
        RobotAdaptiveExecutionAuthorization a = authorization();
        Workspace other = new Workspace("Other", "other");
        AppUser stranger = new AppUser("s@example.test", "hash", "S");
        AuthenticatedUser strangerPrincipal = new AuthenticatedUser(stranger);
        when(auth.currentMembershipFor(strangerPrincipal)).thenReturn(new WorkspaceMembership(other, stranger, WorkspaceRole.OWNER));
        when(authorizations.findByWorkspaceAndId(eq(other), any())).thenReturn(Optional.empty());
        when(authorizations.findByWorkspaceAndIdForUpdate(eq(other), any())).thenReturn(Optional.empty());
        when(proposals.findByWorkspaceAndIdForUpdate(eq(other), any())).thenReturn(Optional.empty());
        when(proposals.findByWorkspaceAndId(eq(other), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.eligibility(strangerPrincipal, a.getId())).hasMessageContaining("not found");
        assertThatThrownBy(() -> service.revoke(strangerPrincipal, a.getId())).hasMessageContaining("not found");
        assertThatThrownBy(() -> service.create(strangerPrincipal, proposal.getId(), null)).hasMessageContaining("not found");
        assertThatThrownBy(() -> service.list(strangerPrincipal, proposal.getId())).hasMessageContaining("not found");
    }

    // ---- helpers ----

    private RobotAdaptiveExecutionAuthorization authorization() { return authorizationFor(proposal); }

    private RobotAdaptiveExecutionAuthorization authorizationFor(RobotChangeProposal p) {
        RobotAdaptiveExecutionAuthorization a = new RobotAdaptiveExecutionAuthorization(workspace, p.getId(), p.getTargetRobotId(),
                p.getCurrentPersonaId(), p.getProposedPersonaId(), p.getSourceExperimentId(), T0, T0.plus(Duration.ofHours(24)), 3, owner, T0);
        storedAuthorizations.add(a);
        return a;
    }


    private RobotChangeProposal pendingProposal() {
        RobotChangeProposal p = new RobotChangeProposal(workspace, UUID.randomUUID(), UUID.randomUUID(), robot.getId(), "Robot", current.getId(),
                "Current", "cfp", target.getId(), "Target", "tfp", proposal.getExpectedRobotConfigFingerprint(), DashboardQuery.Metric.VIEWS,
                DashboardQuery.Window.H72, 10, 10, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, false, new BigDecimal("0.01"), BigDecimal.ONE, "l", "r", "pfp", owner, T0);
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    private AdaptiveGuardrailEvaluation eval(List<RobotAdaptivePolicyModels.Reason> reasons) {
        return new AdaptiveGuardrailEvaluation(workspace, proposal.getId(), robot.getId(), Trigger.CHECK, 3, reasons, 2, 0, 30, null, null,
                72, null, null, 0, RobotAdaptivePolicyModels.Observation.empty(), T0);
    }

    private Persona persona(String name) {
        return new Persona(workspace, name, null, SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL, "Audience", "Voice", null, null, null, null, owner, T0);
    }
}
