package com.fdmultimedia.api.robotchanges;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fdmultimedia.api.analytics.PerformanceInsightProperties;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.experiments.*;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.*;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.*;
import com.fdmultimedia.api.robots.*;
import com.fdmultimedia.api.workspaces.Workspace;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.Pageable;

class AdaptiveGuardrailServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-03T12:00:00Z");
    private final AuthService auth=mock(AuthService.class);private final RobotChangeProposalRepository proposals=mock(RobotChangeProposalRepository.class);
    private final RobotConfigurationRevisionRepository revisions=mock(RobotConfigurationRevisionRepository.class);
    private final RobotAdaptivePolicyService policies=mock(RobotAdaptivePolicyService.class);
    private final AdaptiveGuardrailEvaluationRepository evaluations=mock(AdaptiveGuardrailEvaluationRepository.class);
    private final ExperimentRepository experiments=mock(ExperimentRepository.class);private final RobotRepository robots=mock(RobotRepository.class);
    private final AdaptiveGuardrailStore store=mock(AdaptiveGuardrailStore.class);private final PerformanceInsightProperties thresholds=new PerformanceInsightProperties();
    private final AdaptiveGuardrailService service=new AdaptiveGuardrailService(auth,proposals,revisions,policies,evaluations,experiments,robots,store,thresholds,Clock.fixed(NOW,ZoneOffset.UTC));
    private final Workspace workspace=new Workspace("W","w");private final UUID robotId=UUID.randomUUID();
    private Robot robot;private RobotChangeProposal proposal;

    @BeforeEach void setup(){robot=mock(Robot.class);proposal=mock(RobotChangeProposal.class);when(robot.getId()).thenReturn(robotId);
        when(proposal.getId()).thenReturn(UUID.randomUUID());when(proposal.getCreatedAt()).thenReturn(NOW.minusSeconds(10));
        when(policies.effective(workspace,robotId)).thenReturn(policy(0,true,2,30,72,true,true,true));
        when(revisions.countByRobotIdAndChangeTypeAndCreatedAtGreaterThanEqual(eq(robotId),eq(ChangeType.PERSONA_CHANGE),any())).thenReturn(0L);
        when(revisions.findTopByRobotIdOrderByRevisionDesc(robotId)).thenReturn(Optional.empty());
        when(proposals.findByWorkspaceAndTargetRobotIdOrderByCreatedAtAsc(eq(workspace),eq(robotId),any(Pageable.class))).thenReturn(List.of(proposal));
        when(evaluations.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));}

    @Test void firstAdaptiveChangePassesObservationAndCooldown(){var e=service.evaluateAndPersist(workspace,robot,proposal,Trigger.APPLY);
        assertThat(e.isEligible()).isTrue();assertThat(e.getReasonCodes()).isEmpty();verifyNoInteractions(store);}

    @Test void disabledPolicyBlocksForwardApplyWithoutMutatingAnything(){when(policies.effective(workspace,robotId)).thenReturn(policy(2,false,2,30,72,true,true,true));
        var e=service.evaluateAndPersist(workspace,robot,proposal,Trigger.APPLY);assertThat(e.isEligible()).isFalse();
        assertThat(e.getReasonCodes()).isEqualTo("POLICY_DISABLED");verifyNoInteractions(store);}

    @Test void budgetCountsOnlyForwardChangesInRollingWindow(){when(revisions.countByRobotIdAndChangeTypeAndCreatedAtGreaterThanEqual(eq(robotId),eq(ChangeType.PERSONA_CHANGE),any())).thenReturn(2L);
        var e=service.evaluateAndPersist(workspace,robot,proposal,Trigger.CHECK);assertThat(e.getReasonCodes()).contains("CHANGE_BUDGET_EXHAUSTED");
        verify(revisions).countByRobotIdAndChangeTypeAndCreatedAtGreaterThanEqual(eq(robotId),eq(ChangeType.PERSONA_CHANGE),eq(NOW.minus(Duration.ofDays(30))));}

    @Test void cooldownUsesLatestRevisionIncludingRollbackEpoch(){RobotConfigurationRevision latest=mock(RobotConfigurationRevision.class);when(latest.getId()).thenReturn(UUID.randomUUID());when(latest.getCreatedAt()).thenReturn(NOW.minus(Duration.ofHours(1)));
        when(revisions.findTopByRobotIdOrderByRevisionDesc(robotId)).thenReturn(Optional.of(latest));when(store.observation(any(),any(),any(),any())).thenReturn(new Observation(5,5,5,5,5,BigDecimal.ONE));
        var e=service.evaluateAndPersist(workspace,robot,proposal,Trigger.CHECK);assertThat(e.getReasonCodes()).contains("COOLDOWN_ACTIVE");assertThat(e.getCooldownEndsAt()).isEqualTo(NOW.plus(Duration.ofHours(71)));}

    @Test void zeroCooldownPassesImmediately(){when(policies.effective(workspace,robotId)).thenReturn(policy(4,true,2,30,0,true,true,false));RobotConfigurationRevision latest=mock(RobotConfigurationRevision.class);
        when(latest.getCreatedAt()).thenReturn(NOW);when(revisions.findTopByRobotIdOrderByRevisionDesc(robotId)).thenReturn(Optional.of(latest));
        assertThat(service.evaluateAndPersist(workspace,robot,proposal,Trigger.CHECK).getReasonCodes()).doesNotContain("COOLDOWN_ACTIVE");}

    @Test void cooldownBoundaryPassesAtExactExpiry(){when(policies.effective(workspace,robotId)).thenReturn(policy(4,true,2,30,72,true,true,false));RobotConfigurationRevision latest=mock(RobotConfigurationRevision.class);
        when(latest.getCreatedAt()).thenReturn(NOW.minus(Duration.ofHours(72)));when(revisions.findTopByRobotIdOrderByRevisionDesc(robotId)).thenReturn(Optional.of(latest));
        assertThat(service.evaluateAndPersist(workspace,robot,proposal,Trigger.CHECK).getReasonCodes()).doesNotContain("COOLDOWN_ACTIVE");}

    @Test void activeExperimentAndOlderPendingProposalAreBothReturnedInStableOrder(){UUID experimentId=UUID.randomUUID();Experiment experiment=mock(Experiment.class);when(robot.getExperimentId()).thenReturn(experimentId);
        when(experiments.findByWorkspaceAndId(workspace,experimentId)).thenReturn(Optional.of(experiment));when(experiment.isTerminal()).thenReturn(false);
        RobotChangeProposal older=mock(RobotChangeProposal.class);when(older.getId()).thenReturn(UUID.randomUUID());when(older.getStatus()).thenReturn(Status.APPROVED);when(older.getCreatedAt()).thenReturn(NOW.minusSeconds(20));
        when(proposals.findByWorkspaceAndTargetRobotIdOrderByCreatedAtAsc(eq(workspace),eq(robotId),any(Pageable.class))).thenReturn(List.of(older,proposal));
        assertThat(service.evaluateAndPersist(workspace,robot,proposal,Trigger.CHECK).getReasonCodes()).isEqualTo("ACTIVE_EXPERIMENT,PENDING_CHANGE_EXISTS");}

    @Test void completedSourceExperimentAndTerminalProposalDoNotBlock(){UUID experimentId=UUID.randomUUID();Experiment experiment=mock(Experiment.class);when(robot.getExperimentId()).thenReturn(experimentId);
        when(experiments.findByWorkspaceAndId(workspace,experimentId)).thenReturn(Optional.of(experiment));when(experiment.isTerminal()).thenReturn(true);
        RobotChangeProposal terminal=mock(RobotChangeProposal.class);when(terminal.getId()).thenReturn(UUID.randomUUID());when(terminal.getStatus()).thenReturn(Status.REJECTED);when(terminal.getCreatedAt()).thenReturn(NOW.minusSeconds(20));
        when(proposals.findByWorkspaceAndTargetRobotIdOrderByCreatedAtAsc(eq(workspace),eq(robotId),any(Pageable.class))).thenReturn(List.of(terminal,proposal));
        assertThat(service.evaluateAndPersist(workspace,robot,proposal,Trigger.CHECK).isEligible()).isTrue();}

    @Test void insufficientPostChangeObservationBlocksRegardlessOfPerformanceValue(){RobotConfigurationRevision latest=mock(RobotConfigurationRevision.class);when(latest.getCreatedAt()).thenReturn(NOW.minus(Duration.ofDays(10)));
        when(revisions.findTopByRobotIdOrderByRevisionDesc(robotId)).thenReturn(Optional.of(latest));when(store.observation(any(),any(),any(),any())).thenReturn(new Observation(10,5,5,2,2,new BigDecimal("0.4")));
        assertThat(service.evaluateAndPersist(workspace,robot,proposal,Trigger.CHECK).getReasonCodes()).contains("POST_CHANGE_OBSERVATION_REQUIRED");}

    @Test void missingAnalyticsRemainsUnavailableAndBlocksObservation(){RobotConfigurationRevision latest=mock(RobotConfigurationRevision.class);when(latest.getCreatedAt()).thenReturn(NOW.minus(Duration.ofDays(10)));
        when(revisions.findTopByRobotIdOrderByRevisionDesc(robotId)).thenReturn(Optional.of(latest));when(store.observation(any(),any(),any(),any())).thenReturn(new Observation(5,5,5,0,0,null));
        var e=service.evaluateAndPersist(workspace,robot,proposal,Trigger.CHECK);assertThat(e.getReasonCodes()).contains("POST_CHANGE_OBSERVATION_REQUIRED");assertThat(e.getCoverage()).isNull();}

    @Test void matureLowPerformanceEvidencePassesBecauseGuardrailChecksSufficiencyOnly(){RobotConfigurationRevision latest=mock(RobotConfigurationRevision.class);when(latest.getCreatedAt()).thenReturn(NOW.minus(Duration.ofDays(10)));
        when(revisions.findTopByRobotIdOrderByRevisionDesc(robotId)).thenReturn(Optional.of(latest));when(store.observation(any(),any(),any(),any())).thenReturn(new Observation(10,5,5,5,5,BigDecimal.ONE));
        assertThat(service.evaluateAndPersist(workspace,robot,proposal,Trigger.CHECK).isEligible()).isTrue();}

    @Test void latestPolicyRevisionIsFrozenOnEvaluation(){when(policies.effective(workspace,robotId)).thenReturn(policy(9,true,2,30,0,false,false,false));
        assertThat(service.evaluateAndPersist(workspace,robot,proposal,Trigger.APPLY).getPolicyRevision()).isEqualTo(9);}

    @Test void serviceHasNoMutationOrAiDecisionDependency(){assertThat(Arrays.stream(AdaptiveGuardrailService.class.getDeclaredFields()).map(f->f.getType().getSimpleName()))
            .noneMatch(n->n.contains("AiProvider")||n.contains("Ollama")||n.contains("RobotService")||n.contains("PersonaService"));}

    private PolicySummary policy(int revision,boolean enabled,int max,int days,int cooldown,boolean experiment,boolean pending,boolean observation){
        return new PolicySummary(robotId,revision,revision>0,enabled,max,days,cooldown,experiment,pending,observation,NOW);}
}
