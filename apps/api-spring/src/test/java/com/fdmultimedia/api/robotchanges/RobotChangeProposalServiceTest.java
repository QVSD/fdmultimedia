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
import com.fdmultimedia.api.experiments.*;
import com.fdmultimedia.api.optimization.OptimizationProposal;
import com.fdmultimedia.api.optimization.OptimizationProposalModels;
import com.fdmultimedia.api.optimization.OptimizationProposalRepository;
import com.fdmultimedia.api.optimization.OptimizationProposalService;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.*;
import com.fdmultimedia.api.robots.*;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class RobotChangeProposalServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private final AuthService auth = mock(AuthService.class);
    private final RobotChangeProposalRepository proposals = mock(RobotChangeProposalRepository.class);
    private final RobotConfigurationRevisionRepository revisions = mock(RobotConfigurationRevisionRepository.class);
    private final OptimizationProposalRepository optimizationProposals = mock(OptimizationProposalRepository.class);
    private final ExperimentRepository experiments = mock(ExperimentRepository.class);
    private final ExperimentVariantRepository variants = mock(ExperimentVariantRepository.class);
    private final ExperimentAnalysisService analysisService = mock(ExperimentAnalysisService.class);
    private final RobotRepository robots = mock(RobotRepository.class);
    private final PersonaRepository personas = mock(PersonaRepository.class);
    private final AdaptiveGuardrailService guardrails = mock(AdaptiveGuardrailService.class);
    private final RobotChangeProposalService service = new RobotChangeProposalService(auth, proposals, revisions,
            optimizationProposals, experiments, variants, analysisService, robots, personas, guardrails, Clock.fixed(NOW, ZoneOffset.UTC));

    private final Workspace workspace = new Workspace("Test", "test");
    private final AppUser owner = new AppUser("owner@example.test", "hash", "Owner");
    private final AuthenticatedUser principal = new AuthenticatedUser(owner);

    private final Persona currentPersona = persona("Current");
    private final Persona candidatePersona = persona("Candidate");
    private Robot robot;
    private Experiment experiment;
    private ExperimentVariant variantA;
    private ExperimentVariant variantB;
    private OptimizationProposal sourceProposal;

    private final AtomicReference<RobotChangeProposal> savedProposal = new AtomicReference<>();
    private final AtomicReference<Robot> savedRobot = new AtomicReference<>();
    private final List<RobotConfigurationRevision> savedRevisions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(auth.currentMembershipFor(principal)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        when(guardrails.evaluateAndPersist(any(),any(),any(),any())).thenAnswer(invocation ->
                new AdaptiveGuardrailEvaluation(workspace, invocation.<RobotChangeProposal>getArgument(2).getId(),
                        invocation.<Robot>getArgument(1).getId(), invocation.getArgument(3), 0, List.of(), 2, 0, 30,
                        null, null, 72, null, null, 0, RobotAdaptivePolicyModels.Observation.empty(), NOW));

        robot = new Robot(workspace, "Robot", "d", RobotAutonomyMode.REVIEW_REQUIRED, RobotSourcePolicy.EXISTING_ASSET, null,
                null, null, null, RobotCadenceType.MANUAL_ONLY, null, null, 5, RobotAiPolicy.GENERATE_FOR_REVIEW, currentPersona,
                SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL, null, owner, NOW);

        experiment = new Experiment(workspace, "Persona test", "d", "hyp", ExperimentFactor.PERSONA, DashboardQuery.Window.H72,
                DashboardQuery.Metric.VIEWS, owner, NOW);
        experiment.activate(NOW);
        experiment.complete(NOW.plusSeconds(1));
        robot.update(robot.getName(), robot.getDescription(), robot.getAutonomyMode(), null, robot.getCadenceType(), null, null,
                robot.getMaxRunsPerDay(), robot.getAiPolicy(), robot.getPersona(), robot.getAiLanguageOverride(),
                robot.getAiToneOverride(), experiment.getId(), NOW);

        variantA = new ExperimentVariant(experiment, ExperimentVariantKey.A, "A", currentPersona.getId(), NOW);
        variantB = new ExperimentVariant(experiment, ExperimentVariantKey.B, "B", candidatePersona.getId(), NOW);

        sourceProposal = new OptimizationProposal(workspace, null, 1, currentPersona.getId(), currentPersona.getName(),
                RobotChangeProposalService.personaFingerprint(currentPersona), candidatePersona.getId(), candidatePersona.getName(),
                RobotChangeProposalService.personaFingerprint(candidatePersona), DashboardQuery.Metric.VIEWS,
                DashboardQuery.Window.H72, "TEST", NOW, NOW, 10, 10, 10, 10, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.valueOf(12), BigDecimal.valueOf(2), BigDecimal.valueOf(20), OptimizationProposalModels.Direction.HIGHER_OBSERVED,
                "fp", "rationale", "limitation", owner, NOW);
        sourceProposal.approve(NOW);
        sourceProposal.materialize(experiment.getId(), NOW);

        when(optimizationProposals.findByWorkspaceAndId(workspace, sourceProposal.getId())).thenReturn(Optional.of(sourceProposal));
        when(robots.findByWorkspaceAndId(workspace, robot.getId())).thenReturn(Optional.of(robot));
        when(experiments.findByWorkspaceAndId(workspace, experiment.getId())).thenReturn(Optional.of(experiment));
        when(variants.findByExperimentOrderByVariantKeyAsc(experiment)).thenReturn(List.of(variantA, variantB));
        when(personas.findByWorkspaceAndId(workspace, candidatePersona.getId())).thenReturn(Optional.of(candidatePersona));
        when(personas.findByWorkspaceAndId(workspace, currentPersona.getId())).thenReturn(Optional.of(currentPersona));
        when(analysisService.analyze(principal, experiment.getId())).thenReturn(readyAnalysis());

        when(proposals.saveAndFlush(any())).thenAnswer(i -> { RobotChangeProposal p = i.getArgument(0); savedProposal.set(p); return p; });
        when(robots.saveAndFlush(any())).thenAnswer(i -> { Robot r = i.getArgument(0); savedRobot.set(r); return r; });
        when(revisions.saveAndFlush(any())).thenAnswer(i -> { RobotConfigurationRevision r = i.getArgument(0); savedRevisions.add(r); return r; });
        when(revisions.maxRevision(robot.getId())).thenAnswer(i -> savedRevisions.stream().mapToInt(RobotConfigurationRevision::getRevision).max().orElse(0));
    }

    // ---- eligibility gate ----

    @Test void createRejectsWhenSourceProposalNotMaterialized() {
        OptimizationProposal notMaterialized = new OptimizationProposal(workspace, null, 1, currentPersona.getId(),
                currentPersona.getName(), "fp1", candidatePersona.getId(), candidatePersona.getName(), "fp2",
                DashboardQuery.Metric.VIEWS, DashboardQuery.Window.H72, "TEST", NOW, NOW, 10, 10, 10, 10, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.valueOf(12), BigDecimal.TWO, BigDecimal.valueOf(20),
                OptimizationProposalModels.Direction.HIGHER_OBSERVED, "fp", "r", "l", owner, NOW);
        when(optimizationProposals.findByWorkspaceAndId(workspace, notMaterialized.getId())).thenReturn(Optional.of(notMaterialized));
        assertCode(notMaterialized.getId(), robot.getId(), "SOURCE_PROPOSAL_NOT_MATERIALIZED");
    }

    @Test void createRejectsWhenExperimentNotCompleted() {
        Experiment active = new Experiment(workspace, "x", "d", "h", ExperimentFactor.PERSONA, DashboardQuery.Window.H72,
                DashboardQuery.Metric.VIEWS, owner, NOW);
        active.activate(NOW);
        OptimizationProposal notYetCompleted = new OptimizationProposal(workspace, null, 1, currentPersona.getId(),
                currentPersona.getName(), "fp1", candidatePersona.getId(), candidatePersona.getName(), "fp2",
                DashboardQuery.Metric.VIEWS, DashboardQuery.Window.H72, "TEST", NOW, NOW, 10, 10, 10, 10, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.valueOf(12), BigDecimal.valueOf(2), BigDecimal.valueOf(20),
                OptimizationProposalModels.Direction.HIGHER_OBSERVED, "fp", "r", "l", owner, NOW);
        notYetCompleted.approve(NOW);
        notYetCompleted.materialize(active.getId(), NOW);
        when(optimizationProposals.findByWorkspaceAndId(workspace, notYetCompleted.getId())).thenReturn(Optional.of(notYetCompleted));
        when(experiments.findByWorkspaceAndId(workspace, active.getId())).thenReturn(Optional.of(active));
        assertCode(notYetCompleted.getId(), robot.getId(), "EXPERIMENT_NOT_COMPLETED");
    }

    @Test void createRejectsWhenVariantBDoesNotMatchCandidatePersona() {
        ExperimentVariant mismatch = new ExperimentVariant(experiment, ExperimentVariantKey.B, "B", UUID.randomUUID(), NOW);
        when(variants.findByExperimentOrderByVariantKeyAsc(experiment)).thenReturn(List.of(variantA, mismatch));
        assertCode(sourceProposal.getId(), robot.getId(), "SOURCE_PROPOSAL_VARIANT_MISMATCH");
    }

    @Test void createRejectsRobotNotEnrolledInExperiment() {
        robot.update(robot.getName(), robot.getDescription(), robot.getAutonomyMode(), null, robot.getCadenceType(), null, null,
                robot.getMaxRunsPerDay(), robot.getAiPolicy(), robot.getPersona(), robot.getAiLanguageOverride(),
                robot.getAiToneOverride(), UUID.randomUUID(), NOW);
        assertCode(sourceProposal.getId(), robot.getId(), "TARGET_ROBOT_NOT_ENROLLED");
    }

    @Test void createRejectsRobotThatDoesNotSupportPersona() {
        Robot noAi = new Robot(workspace, "NoAi", "d", RobotAutonomyMode.REVIEW_REQUIRED, RobotSourcePolicy.EXISTING_ASSET, null,
                null, null, null, RobotCadenceType.MANUAL_ONLY, null, null, 5, owner, NOW);
        noAi.update(noAi.getName(), noAi.getDescription(), noAi.getAutonomyMode(), null, noAi.getCadenceType(), null, null,
                noAi.getMaxRunsPerDay(), RobotAiPolicy.NO_AI, null, null, null, experiment.getId(), NOW);
        when(robots.findByWorkspaceAndId(workspace, noAi.getId())).thenReturn(Optional.of(noAi));
        assertCode(sourceProposal.getId(), noAi.getId(), "TARGET_ROBOT_DOES_NOT_SUPPORT_PERSONA");
    }

    @Test void createRejectsWhenAnalysisIsNotReady() {
        when(analysisService.analyze(principal, experiment.getId())).thenReturn(notReadyAnalysis());
        assertCode(sourceProposal.getId(), robot.getId(), "ANALYSIS_NOT_READY");
    }

    @Test void createRejectsArchivedCandidatePersona() {
        candidatePersona.archive(NOW);
        assertCode(sourceProposal.getId(), robot.getId(), "CANDIDATE_PERSONA_UNAVAILABLE");
    }

    @Test void eligibilityEndpointReportsSameGateWithoutThrowing() {
        candidatePersona.archive(NOW);
        Eligibility result = service.eligibility(principal, sourceProposal.getId(), robot.getId());
        assertThat(result.eligible()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("CANDIDATE_PERSONA_UNAVAILABLE");
    }

    // ---- create: snapshot correctness ----

    @Test void createFreezesExactSnapshotAndNeutralWording() {
        Summary s = createProposal();
        assertThat(s.status()).isEqualTo(Status.READY_FOR_REVIEW);
        assertThat(s.factor()).isEqualTo("PERSONA");
        assertThat(s.engineVersion()).isEqualTo(RobotChangeProposalService.ENGINE_VERSION);
        assertThat(s.currentPersonaId()).isEqualTo(currentPersona.getId());
        assertThat(s.proposedPersonaId()).isEqualTo(candidatePersona.getId());
        assertThat(s.baselineSampleCount()).isEqualTo(10);
        assertThat(s.candidateSampleCount()).isEqualTo(12);
        assertThat(s.rationale()).doesNotContainIgnoringCase("winner").doesNotContainIgnoringCase("best")
                .doesNotContainIgnoringCase("guaranteed").doesNotContainIgnoringCase("upgrade");
        assertThat(s.limitations()).isNotEmpty();
    }

    // ---- fingerprint stability ----

    @Test void robotConfigFingerprintIsStableForSamePersonaAndChangesWithPersona() {
        String fp1 = RobotChangeProposalService.robotConfigFingerprint(robot.getId(), currentPersona.getId());
        String fp2 = RobotChangeProposalService.robotConfigFingerprint(robot.getId(), currentPersona.getId());
        String fp3 = RobotChangeProposalService.robotConfigFingerprint(robot.getId(), candidatePersona.getId());
        assertThat(fp1).isEqualTo(fp2).isNotEqualTo(fp3);
    }

    @Test void personaFingerprintIsStableAndChangesWithContent() {
        String fp1 = RobotChangeProposalService.personaFingerprint(currentPersona);
        String fp2 = RobotChangeProposalService.personaFingerprint(currentPersona);
        assertThat(fp1).isEqualTo(fp2);
        currentPersona.update("Changed", null, SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL, "Audience", "Voice", null, null, null, null, NOW.plusSeconds(1));
        assertThat(RobotChangeProposalService.personaFingerprint(currentPersona)).isNotEqualTo(fp1);
    }

    // ---- approve / reject: no mutation ----

    @Test void approveAndRejectNeverTouchRobotOrRevisions() {
        RobotChangeProposal p = createEntity();
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        clearInvocations(robots);
        Summary approved = service.approve(principal, p.getId());
        assertThat(approved.status()).isEqualTo(Status.APPROVED);
        verify(robots,never()).saveAndFlush(any());
        verifyNoInteractions(revisions);

        RobotChangeProposal p2 = createEntity();
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p2.getId())).thenReturn(Optional.of(p2));
        clearInvocations(robots);
        Summary rejected = service.reject(principal, p2.getId());
        assertThat(rejected.status()).isEqualTo(Status.REJECTED);
        verify(robots,never()).saveAndFlush(any());
        verifyNoInteractions(revisions);
    }

    @Test void approveRequiresReadyForReview() {
        RobotChangeProposal p = createEntity();
        p.approve(NOW);
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        assertThatThrownBy(() -> service.approve(principal, p.getId())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PROPOSAL_NOT_READY_FOR_REVIEW");
    }

    // ---- apply ----

    @Test void applyWithoutApprovalIsRejected() {
        RobotChangeProposal p = createEntity();
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        clearInvocations(robots);
        assertThatThrownBy(() -> service.apply(principal, p.getId())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PROPOSAL_NOT_APPROVED");
        verifyNoInteractions(robots);
    }

    @Test void applyChangesPersonaExactlyOnceAndCreatesOneRevision() {
        RobotChangeProposal p = createEntity();
        p.approve(NOW);
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));

        Summary result = service.apply(principal, p.getId());

        assertThat(result.status()).isEqualTo(Status.APPLIED);
        assertThat(robot.getPersona().getId()).isEqualTo(candidatePersona.getId());
        verify(robots, times(1)).saveAndFlush(any());
        assertThat(savedRevisions).hasSize(1);
        assertThat(savedRevisions.get(0).getRevision()).isEqualTo(1);
        assertThat(savedRevisions.get(0).getNewPersonaId()).isEqualTo(candidatePersona.getId());
        assertThat(savedRevisions.get(0).getPreviousPersonaId()).isEqualTo(currentPersona.getId());
        assertThat(savedRevisions.get(0).getSourceProposalId()).isEqualTo(p.getId());
        assertThat(savedRevisions.get(0).getGuardrailEvaluationId()).isNotNull();
        assertThat(savedRevisions.get(0).getGuardrailEngineVersion()).isEqualTo(AdaptiveGuardrailService.ENGINE_VERSION);
    }

    @Test void applyIsIdempotentAndNeverCreatesASecondRevision() {
        RobotChangeProposal p = createEntity();
        p.approve(NOW);
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));

        service.apply(principal, p.getId());
        Summary second = service.apply(principal, p.getId());

        assertThat(second.status()).isEqualTo(Status.APPLIED);
        verify(robots, times(1)).saveAndFlush(any());
        assertThat(savedRevisions).hasSize(1);
    }

    @Test void temporaryGuardrailBlockKeepsApprovedProposalAndRobotUnchanged() {
        RobotChangeProposal p=createEntity();p.approve(NOW);
        when(proposals.findByWorkspaceAndIdForUpdate(workspace,p.getId())).thenReturn(Optional.of(p));
        when(robots.findByWorkspaceAndIdForUpdate(workspace,robot.getId())).thenReturn(Optional.of(robot));
        doReturn(new AdaptiveGuardrailEvaluation(workspace,p.getId(),robot.getId(),RobotAdaptivePolicyModels.Trigger.APPLY,
                        1,List.of(RobotAdaptivePolicyModels.Reason.COOLDOWN_ACTIVE),2,1,30,UUID.randomUUID(),NOW,
                        72,NOW.plus(Duration.ofHours(72)),null,0,RobotAdaptivePolicyModels.Observation.empty(),NOW))
                .when(guardrails).evaluateAndPersist(workspace,robot,p,RobotAdaptivePolicyModels.Trigger.APPLY);

        Summary result=service.apply(principal,p.getId());

        assertThat(result.status()).isEqualTo(Status.APPROVED);
        assertThat(robot.getPersona().getId()).isEqualTo(currentPersona.getId());
        verify(robots,never()).saveAndFlush(any());
        assertThat(savedRevisions).isEmpty();
    }

    @Test void applyRejectsAndMarksStaleWhenRobotConfigDiverged() {
        RobotChangeProposal p = createEntity();
        p.approve(NOW);
        Persona somebodyElse = persona("SomebodyElse");
        robot.applyPersona(somebodyElse, NOW.plusSeconds(10));
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));

        Summary result = service.apply(principal, p.getId());

        assertThat(result.status()).isEqualTo(Status.STALE);
        assertThat(p.getStatus()).isEqualTo(Status.STALE);
        verify(robots, never()).saveAndFlush(any());
        assertThat(savedRevisions).isEmpty();
    }

    @Test void applyRejectsAndMarksStaleWhenCandidatePersonaArchived() {
        RobotChangeProposal p = createEntity();
        p.approve(NOW);
        candidatePersona.archive(NOW.plusSeconds(5));
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));

        Summary result = service.apply(principal, p.getId());

        assertThat(result.status()).isEqualTo(Status.STALE);
        assertThat(p.getStatus()).isEqualTo(Status.STALE);
        verify(robots, never()).saveAndFlush(any());
    }

    @Test void applyNeverTouchesExperimentRepository() {
        RobotChangeProposal p = createEntity();
        p.approve(NOW);
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        clearInvocations(experiments);

        service.apply(principal, p.getId());

        verifyNoInteractions(experiments);
    }

    // ---- rollback ----

    @Test void rollbackFullCycleRestoresOriginalPersonaAndKeepsEvidenceUnchanged() {
        RobotChangeProposal p = createEntity();
        p.approve(NOW);
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        service.apply(principal, p.getId());
        RobotConfigurationRevision applied = savedRevisions.get(0);
        UUID expectedSourceOptimizationProposalId = p.getSourceOptimizationProposalId();
        UUID expectedSourceExperimentId = p.getSourceExperimentId();

        when(revisions.findByWorkspaceAndRobotIdAndId(workspace, robot.getId(), applied.getId())).thenReturn(Optional.of(applied));
        when(revisions.findByRobotIdAndRollbackOfRevisionId(robot.getId(), applied.getId())).thenReturn(Optional.empty());

        RevisionSummary rollback = service.rollback(principal, robot.getId(), applied.getId(), new RollbackRequest("manual revert"));

        assertThat(rollback.changeType()).isEqualTo(ChangeType.ROLLBACK);
        assertThat(rollback.revision()).isEqualTo(2);
        assertThat(rollback.newPersonaId()).isEqualTo(currentPersona.getId());
        assertThat(robot.getPersona().getId()).isEqualTo(currentPersona.getId());
        assertThat(p.getStatus()).isEqualTo(Status.ROLLED_BACK);
        assertThat(p.getSourceOptimizationProposalId()).isEqualTo(expectedSourceOptimizationProposalId);
        assertThat(p.getSourceExperimentId()).isEqualTo(expectedSourceExperimentId);
        assertThat(p.getAbsoluteMeanDifference()).isNotNull();
    }

    @Test void rollbackIsIdempotentAndNeverCreatesADuplicateRevision() {
        RobotChangeProposal p = createEntity();
        p.approve(NOW);
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        service.apply(principal, p.getId());
        RobotConfigurationRevision applied = savedRevisions.get(0);
        when(revisions.findByWorkspaceAndRobotIdAndId(workspace, robot.getId(), applied.getId())).thenReturn(Optional.of(applied));

        RevisionSummary first = service.rollback(principal, robot.getId(), applied.getId(), null);
        when(revisions.findByRobotIdAndRollbackOfRevisionId(robot.getId(), applied.getId()))
                .thenReturn(Optional.of(savedRevisions.get(1)));
        RevisionSummary second = service.rollback(principal, robot.getId(), applied.getId(), null);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(savedRevisions).hasSize(2);
    }

    @Test void rollbackOfASupersededRevisionIsRejectedNoTimeTravel() {
        RobotChangeProposal p = createEntity();
        p.approve(NOW);
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        service.apply(principal, p.getId());
        RobotConfigurationRevision revision1 = savedRevisions.get(0);
        // Simulate a second, independent change superseding revision 1 (revision 2 now current).
        robot.applyPersona(persona("Third"), NOW.plusSeconds(20));
        savedRevisions.add(new RobotConfigurationRevision(workspace, robot.getId(), 2, ChangeType.PERSONA_CHANGE,
                currentPersona.getId(), currentPersona.getName(), robot.getPersona().getId(), robot.getPersona().getName(),
                "fpA", "fpB", null, null, null, owner, null, NOW.plusSeconds(20)));
        when(revisions.findByWorkspaceAndRobotIdAndId(workspace, robot.getId(), revision1.getId())).thenReturn(Optional.of(revision1));
        when(revisions.findByRobotIdAndRollbackOfRevisionId(robot.getId(), revision1.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollback(principal, robot.getId(), revision1.getId(), null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ROLLBACK_TARGET_NOT_CURRENT");
    }

    @Test void rollbackBlockedWhenPreviousPersonaArchived() {
        RobotChangeProposal p = createEntity();
        p.approve(NOW);
        when(proposals.findByWorkspaceAndIdForUpdate(workspace, p.getId())).thenReturn(Optional.of(p));
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        service.apply(principal, p.getId());
        RobotConfigurationRevision applied = savedRevisions.get(0);
        when(revisions.findByWorkspaceAndRobotIdAndId(workspace, robot.getId(), applied.getId())).thenReturn(Optional.of(applied));
        when(revisions.findByRobotIdAndRollbackOfRevisionId(robot.getId(), applied.getId())).thenReturn(Optional.empty());
        currentPersona.archive(NOW.plusSeconds(30));

        assertThatThrownBy(() -> service.rollback(principal, robot.getId(), applied.getId(), null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ROLLBACK_PERSONA_ARCHIVED");
        assertThat(robot.getPersona().getId()).isEqualTo(candidatePersona.getId());
    }

    // ---- architecture / dependency direction ----

    @Test void noLlmOrAiProviderDependencyAnywhereInTheApplyPath() {
        assertThat(Arrays.stream(RobotChangeProposalService.class.getDeclaredFields()).map(f -> f.getType().getSimpleName()))
                .noneMatch(n -> n.contains("AiProvider") || n.contains("LanguageModel") || n.contains("Ollama"));
    }

    @Test void phase17gAnd17hNeverDependOnPhase17i() {
        assertThat(Arrays.stream(OptimizationProposalService.class.getDeclaredFields()).map(f -> f.getType().getSimpleName()))
                .noneMatch(n -> n.contains("RobotChangeProposal") || n.contains("RobotConfigurationRevision"));
        assertThat(Arrays.stream(CampaignPerformanceService.class.getDeclaredFields()).map(f -> f.getType().getSimpleName()))
                .noneMatch(n -> n.contains("RobotChangeProposal") || n.contains("RobotConfigurationRevision"));
    }

    @Test void controllerExposesNoWorkerFacingParameterTypes() {
        boolean anyWorkerParam = Arrays.stream(RobotChangeProposalController.class.getDeclaredMethods())
                .flatMap(m -> Arrays.stream(m.getParameterTypes())).anyMatch(t -> t.getSimpleName().contains("Worker"));
        assertThat(anyWorkerParam).isFalse();
    }

    @Test void workspaceScopedReadsDoNotRevealForeignProposals() {
        UUID foreignId = UUID.randomUUID();
        when(proposals.findByWorkspaceAndId(workspace, foreignId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(principal, foreignId)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    // ---- helpers ----

    private void assertCode(UUID sourceId, UUID targetRobotId, String code) {
        assertThatThrownBy(() -> service.create(principal, new CreateRequest(sourceId, targetRobotId)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining(code);
    }

    private Summary createProposal() {
        return service.create(principal, new CreateRequest(sourceProposal.getId(), robot.getId()));
    }

    private RobotChangeProposal createEntity() {
        createProposal();
        return savedProposal.get();
    }

    private Persona persona(String name) {
        return new Persona(workspace, name, null, SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL, "Audience", "Voice", null, null, null, null, owner, NOW);
    }

    private ExperimentAnalysisResponse readyAnalysis() {
        return new ExperimentAnalysisResponse(ExperimentAnalysisService.ANALYSIS_VERSION, experiment.getId(), experiment.getName(),
                ExperimentStatus.COMPLETED, ExperimentFactor.PERSONA, "H72", "VIEWS", new BigDecimal("0.95"), null, List.of("limitation one", "limitation two"),
                population(AnalysisStatus.READY, 10, 12), population(AnalysisStatus.READY, 10, 12));
    }

    private ExperimentAnalysisResponse notReadyAnalysis() {
        return new ExperimentAnalysisResponse(ExperimentAnalysisService.ANALYSIS_VERSION, experiment.getId(), experiment.getName(),
                ExperimentStatus.COMPLETED, ExperimentFactor.PERSONA, "H72", "VIEWS", new BigDecimal("0.95"), null, List.of("limitation"),
                population(AnalysisStatus.INSUFFICIENT_SAMPLE, 1, 1), population(AnalysisStatus.INSUFFICIENT_SAMPLE, 1, 1));
    }

    private ExperimentPopulationAnalysis population(AnalysisStatus status, long sampleA, long sampleB) {
        boolean ready = status == AnalysisStatus.READY;
        ExperimentVariantAnalysis a = new ExperimentVariantAnalysis(ExperimentVariantKey.A, "A", sampleA, 0, sampleA, sampleA, 0,
                sampleA, sampleA, 0, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN);
        ExperimentVariantAnalysis b = new ExperimentVariantAnalysis(ExperimentVariantKey.B, "B", sampleB, 0, sampleB, sampleB, 0,
                sampleB, sampleB, 0, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(12), BigDecimal.valueOf(12), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(12));
        ExperimentEffectEstimate effect = new ExperimentEffectEstimate(BigDecimal.valueOf(2), BigDecimal.valueOf(20),
                ready ? BigDecimal.ONE : null, ready ? BigDecimal.valueOf(18) : null, ready ? BigDecimal.ONE : null,
                ready ? BigDecimal.valueOf(3) : null, ready ? Boolean.FALSE : null, ready ? new BigDecimal("0.01") : null,
                ready ? BigDecimal.ONE : null);
        return new ExperimentPopulationAnalysis(AnalysisPopulation.ASSIGNED_OBSERVED, status, a, b, effect);
    }
}
