package com.fdmultimedia.api.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryService;
import com.fdmultimedia.api.analytics.CampaignPerformanceReview;
import com.fdmultimedia.api.analytics.CampaignPerformanceReviewRepository;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.experiments.ExperimentRepository;
import com.fdmultimedia.api.optimization.AutonomousProposalModels.*;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.*;
import com.fdmultimedia.api.personas.*;
import com.fdmultimedia.api.robotchanges.*;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.ProposalAutomationMode;
import com.fdmultimedia.api.robots.*;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

class AutonomousProposalServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-04T12:00:00Z");
    private final AuthService auth=mock(AuthService.class);private final RobotRepository robots=mock(RobotRepository.class);
    private final RobotAdaptivePolicyRepository policies=mock(RobotAdaptivePolicyRepository.class);
    private final CampaignPerformanceReviewRepository reviews=mock(CampaignPerformanceReviewRepository.class);
    private final PersonaRepository personas=mock(PersonaRepository.class);
    private final OptimizationProposalRepository proposals=mock(OptimizationProposalRepository.class);
    private final OptimizationProposalService proposalService=mock(OptimizationProposalService.class);
    private final RobotChangeProposalRepository robotChanges=mock(RobotChangeProposalRepository.class);
    private final ExperimentRepository experiments=mock(ExperimentRepository.class);
    private final RobotConfigurationRevisionRepository revisions=mock(RobotConfigurationRevisionRepository.class);
    private final AdaptiveMemoryService memory=mock(AdaptiveMemoryService.class);
    private final AutonomousProposalService service=new AutonomousProposalService(auth,robots,policies,reviews,personas,
            proposals,proposalService,robotChanges,experiments,revisions,memory);
    private final Workspace workspace=new Workspace("Workspace","workspace");
    private final AppUser owner=new AppUser("owner@example.test","hash","Owner");
    private final AuthenticatedUser principal=new AuthenticatedUser(owner);
    private final UUID robotId=UUID.randomUUID(),reviewId=UUID.randomUUID();private final Robot robot=mock(Robot.class);
    private final Persona baseline=persona("Baseline");private final Persona candidate=persona("Candidate");
    private final CampaignPerformanceReview review=mock(CampaignPerformanceReview.class);
    private final RobotRun run=mock(RobotRun.class);

    @BeforeEach void setUp(){
        when(memory.screen(any(),any())).thenAnswer(i->new AdaptiveMemoryService.Screen(i.getArgument(0),i.getArgument(1),Map.of(),NOW));
        when(memory.screenReadOnly(any(),any())).thenAnswer(i->new AdaptiveMemoryService.Screen(i.getArgument(0),i.getArgument(1),Map.of(),NOW));
        when(auth.currentMembershipFor(principal)).thenReturn(new WorkspaceMembership(workspace,owner,WorkspaceRole.OWNER));
        when(robot.getId()).thenReturn(robotId);when(robot.getWorkspace()).thenReturn(workspace);when(robot.getPersona()).thenReturn(baseline);
        when(robots.findByWorkspaceAndId(workspace,robotId)).thenReturn(Optional.of(robot));
        when(robots.findByIdForUpdate(robotId)).thenReturn(Optional.of(robot));
        when(review.getId()).thenReturn(reviewId);when(review.getRobotRun()).thenReturn(run);
        when(run.getCreatedAt()).thenReturn(NOW);when(reviews.findCanonicalReadyForRobot(eq(workspace),eq(robotId),any(Pageable.class))).thenReturn(List.of(review));
        when(proposals.countUnresolvedForRobot(robotId)).thenReturn(0L);when(robotChanges.countPendingForRobot(workspace,robotId)).thenReturn(0L);
        when(revisions.findTopByRobotIdOrderByRevisionDesc(robotId)).thenReturn(Optional.empty());
        when(personas.findByWorkspaceAndStatusOrderByIdAsc(eq(workspace),eq(PersonaStatus.ACTIVE),any(Pageable.class))).thenReturn(List.of(candidate));
        AutonomousEvidence evidence=evidence(candidate,Direction.LOWER_OBSERVED,"semantic-one");
        when(proposalService.evaluateAutonomous(workspace,reviewId,candidate.getId())).thenReturn(evidence);
    }

    @Test void manualAndDisabledPoliciesNeverCreateAndReasonsHaveStableOrder(){
        RobotAdaptivePolicy disabled=policy(false,ProposalAutomationMode.AUTO_PROPOSE);
        when(policies.findByWorkspaceAndRobotId(workspace,robotId)).thenReturn(Optional.of(disabled));
        Evaluation disabledResult=service.dryRun(principal,robotId);
        assertThat(disabledResult.reasons()).containsExactly(Reason.ADAPTIVE_POLICY_DISABLED);
        when(policies.findByWorkspaceAndRobotId(workspace,robotId)).thenReturn(Optional.empty());
        assertThat(service.dryRun(principal,robotId).reasons()).startsWith(Reason.MANUAL_ONLY);
        verify(proposalService,never()).createAutonomous(any(),any(),any(),any(),any(),anyInt(),any(),any());
    }

    @Test void canonicalCandidateScanIsBoundedStableAndHasNoDirectionPreference(){
        when(policies.findByWorkspaceAndRobotId(workspace,robotId)).thenReturn(Optional.of(policy(true,ProposalAutomationMode.AUTO_PROPOSE)));
        Evaluation result=service.dryRun(principal,robotId);
        assertThat(result.eligible()).isTrue();assertThat(result.selectedCandidatePersonaId()).isEqualTo(candidate.getId());
        assertThat(result.candidateCountConsidered()).isEqualTo(1);
        verify(personas).findByWorkspaceAndStatusOrderByIdAsc(eq(workspace),eq(PersonaStatus.ACTIVE),
                argThat(p->p.getPageSize()==AutonomousProposalService.CANDIDATE_LIMIT+1));
        verify(proposalService).evaluateAutonomous(workspace,review.getId(),candidate.getId());
    }

    @Test void staleEvidenceBlocksCurrentEpochAndPreservesReasonOrdering(){
        when(policies.findByWorkspaceAndRobotId(workspace,robotId)).thenReturn(Optional.of(policy(true,ProposalAutomationMode.AUTO_PROPOSE)));
        RobotConfigurationRevision latest=mock(RobotConfigurationRevision.class);when(latest.getCreatedAt()).thenReturn(NOW.plusSeconds(1));
        when(revisions.findTopByRobotIdOrderByRevisionDesc(robotId)).thenReturn(Optional.of(latest));
        Evaluation result=service.dryRun(principal,robotId);
        assertThat(result.reasons()).containsExactly(Reason.STALE_EVIDENCE,Reason.POST_CHANGE_OBSERVATION_REQUIRED);
        verifyNoInteractions(proposalService);
    }

    @Test void candidateEvaluationNeverExceedsHardBound(){
        when(policies.findByWorkspaceAndRobotId(workspace,robotId)).thenReturn(Optional.of(policy(true,ProposalAutomationMode.AUTO_PROPOSE)));
        List<Persona> many=new ArrayList<>();for(int i=0;i<AutonomousProposalService.CANDIDATE_LIMIT+1;i++)many.add(persona("Candidate "+i));
        when(personas.findByWorkspaceAndStatusOrderByIdAsc(eq(workspace),eq(PersonaStatus.ACTIVE),any(Pageable.class))).thenReturn(many);
        when(proposalService.evaluateAutonomous(eq(workspace),eq(reviewId),any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"NO_MATERIAL_OBSERVED_DIFFERENCE"));
        Evaluation result=service.dryRun(principal,robotId);
        assertThat(result.candidateCountConsidered()).isEqualTo(AutonomousProposalService.CANDIDATE_LIMIT);
        verify(proposalService,times(AutonomousProposalService.CANDIDATE_LIMIT)).evaluateAutonomous(eq(workspace),eq(reviewId),any());
    }

    @Test void rejectedCandidateDoesNotPoisonTheOuterReadOnlyEvaluationTransaction() throws Exception {
        Transactional boundary=OptimizationProposalService.class
                .getMethod("evaluateAutonomous",Workspace.class,UUID.class,UUID.class)
                .getAnnotation(Transactional.class);
        assertThat(boundary).isNotNull();
        assertThat(boundary.noRollbackFor()).contains(ResponseStatusException.class);
    }

    @Test void autonomousCreationAlwaysOwnsANewTransactionForAfterCommitAndScheduledTriggers() throws Exception {
        Transactional boundary=AutonomousProposalService.class
                .getMethod("evaluateAndCreate",UUID.class,String.class).getAnnotation(Transactional.class);
        assertThat(boundary).isNotNull();
        assertThat(boundary.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test void duplicateOpportunityIsSuppressedAndRejectedProposalIsNotRecreated(){
        when(policies.findByWorkspaceAndRobotId(workspace,robotId)).thenReturn(Optional.of(policy(true,ProposalAutomationMode.AUTO_PROPOSE)));
        OptimizationProposal existing=mock(OptimizationProposal.class);UUID proposalId=UUID.randomUUID();when(existing.getId()).thenReturn(proposalId);
        when(proposals.findByAutomationOpportunityFingerprint(anyString())).thenReturn(Optional.of(existing));
        Evaluation result=service.dryRun(principal,robotId);
        assertThat(result.eligible()).isFalse();assertThat(result.reasons()).containsExactly(Reason.DUPLICATE_OPPORTUNITY);
        assertThat(result.existingProposalId()).isEqualTo(proposalId);
    }

    @Test void autonomousCreationStopsAtReadyForReviewProposalAndHasNoMutationOrAiDependencies(){
        RobotAdaptivePolicy policy=policy(true,ProposalAutomationMode.AUTO_PROPOSE);
        when(policies.findForUpdate(workspace,robotId)).thenReturn(Optional.of(policy));
        Summary created=mock(Summary.class);UUID proposalId=UUID.randomUUID();when(created.id()).thenReturn(proposalId);
        when(created.status()).thenReturn(Status.READY_FOR_REVIEW);when(created.evidenceFingerprint()).thenReturn("evidence");
        when(created.automationOpportunityFingerprint()).thenReturn("opportunity");
        when(proposalService.createAutonomous(eq(workspace),eq(owner),eq(reviewId),eq(candidate.getId()),eq(robotId),
                eq(policy.getRevision()),eq("RECONCILIATION"),eq(AutonomousProposalService.ENGINE_VERSION))).thenReturn(created);
        Evaluation result=service.evaluateAndCreate(robotId,"RECONCILIATION");
        assertThat(result.eligible()).isTrue();assertThat(result.existingProposalId()).isEqualTo(proposalId);
        verify(proposalService).createAutonomous(any(),any(),any(),any(),any(),anyInt(),any(),any());
        assertThat(Arrays.stream(AutonomousProposalService.class.getDeclaredFields()).map(f->f.getType().getSimpleName()))
                .noneMatch(n->n.contains("AiProvider")||n.equals("RobotService")||n.equals("ExperimentService"));
        assertThat(Arrays.stream(com.fdmultimedia.api.analytics.CampaignPerformanceService.class.getDeclaredFields())
                .map(f->f.getType().getSimpleName())).noneMatch(n->n.contains("AutonomousProposal")||n.contains("OptimizationProposal"));
        verify(robots,never()).save(any());
    }

    @Test void autonomousEndpointIsReadOnlyHumanOnlyAndWorkerAuthCannotReachIt() throws Exception {
        var controller=AutonomousProposalController.class;
        assertThat(Arrays.stream(controller.getDeclaredMethods()).flatMap(m->Arrays.stream(m.getParameterTypes()))
                .map(Class::getSimpleName)).noneMatch(n->n.contains("Worker"));
        assertThat(Arrays.stream(controller.getDeclaredMethods()).filter(m->m.isAnnotationPresent(
                org.springframework.web.bind.annotation.PostMapping.class)||m.isAnnotationPresent(
                org.springframework.web.bind.annotation.PutMapping.class))).isEmpty();
        assertThat(controller.getMethod("eligibility",AuthenticatedUser.class,UUID.class)
                .getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value()[0])
                .startsWith("/api/robots/");
        assertThat(new RobotAdaptivePolicy(workspace,robotId,owner,NOW).getProposalAutomationMode())
                .isEqualTo(ProposalAutomationMode.MANUAL_ONLY);
    }

    // ---- Phase 17N: adaptive memory screening (filter only, canonical order and bound unchanged) ----

    private com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Memory suppressedMemory(UUID to){
        return new com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Memory(workspace.getId(),robotId,baseline.getId(),to,
                com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Outcome.ROLLED_BACK,1,1,1,0,NOW,NOW,null,null,null,null,
                NOW.minusSeconds(86400),NOW,null,null,null,null,null,3);}
    private void screenWith(Persona... suppressed){
        Map<com.fdmultimedia.api.adaptivememory.AdaptiveMemoryProjector.Key,com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Memory> map=new HashMap<>();
        for(Persona p:suppressed)map.put(new com.fdmultimedia.api.adaptivememory.AdaptiveMemoryProjector.Key(robotId,baseline.getId(),p.getId()),suppressedMemory(p.getId()));
        when(memory.screen(any(),any())).thenAnswer(i->new AdaptiveMemoryService.Screen(i.getArgument(0),i.getArgument(1),map,NOW));
        when(memory.screenReadOnly(any(),any())).thenAnswer(i->new AdaptiveMemoryService.Screen(i.getArgument(0),i.getArgument(1),map,NOW));}
    private void autoPropose(){when(policies.findByWorkspaceAndRobotId(workspace,robotId)).thenReturn(Optional.of(policy(true,ProposalAutomationMode.AUTO_PROPOSE)));}
    private void validCandidates(Persona... list){when(personas.findByWorkspaceAndStatusOrderByIdAsc(eq(workspace),eq(PersonaStatus.ACTIVE),any(Pageable.class))).thenReturn(List.of(list));
        for(Persona c:list)when(proposalService.evaluateAutonomous(workspace,reviewId,c.getId())).thenReturn(evidence(c,Direction.LOWER_OBSERVED,"semantic-"+c.getName()));}

    @Test void suppressedFirstCandidateIsSkippedAndTheNextCanonicalCandidateIsSelectedInOrder(){
        autoPropose();Persona second=persona("Second");validCandidates(candidate,second);screenWith(candidate);
        Evaluation result=service.dryRun(principal,robotId);
        assertThat(result.eligible()).isTrue();assertThat(result.selectedCandidatePersonaId()).isEqualTo(second.getId());
        assertThat(result.candidateCountConsidered()).isEqualTo(2);
        assertThat(result.memorySkippedCandidates()).hasSize(1);
        assertThat(result.memorySkippedCandidates().get(0).candidatePersonaId()).isEqualTo(candidate.getId());
        assertThat(result.memorySkippedCandidates().get(0).reasons()).contains("ROLLED_BACK");
        var order=inOrder(proposalService);order.verify(proposalService).evaluateAutonomous(workspace,reviewId,candidate.getId());
        order.verify(proposalService).evaluateAutonomous(workspace,reviewId,second.getId());
    }

    @Test void whenEveryValidCandidateIsSuppressedTheResultIsExplicitAndNothingIsCreated(){
        autoPropose();Persona second=persona("Second");validCandidates(candidate,second);screenWith(candidate,second);
        Evaluation result=service.dryRun(principal,robotId);
        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).containsExactly(Reason.ALL_CANDIDATES_MEMORY_SUPPRESSED);
        assertThat(result.memorySkippedCandidates()).hasSize(2);
        RobotAdaptivePolicy policy=policy(true,ProposalAutomationMode.AUTO_PROPOSE);when(policies.findForUpdate(workspace,robotId)).thenReturn(Optional.of(policy));
        assertThat(service.evaluateAndCreate(robotId,"RECONCILIATION").eligible()).isFalse();
        verify(proposalService,never()).createAutonomous(any(),any(),any(),any(),any(),anyInt(),any(),any());
    }

    @Test void memoryOnlyScreensCandidatesThatPassedTheCanonicalGateAndKeepsTheHardBound(){
        autoPropose();Persona rejectedBy17H=persona("Rejected");validCandidates(candidate);
        when(personas.findByWorkspaceAndStatusOrderByIdAsc(eq(workspace),eq(PersonaStatus.ACTIVE),any(Pageable.class))).thenReturn(List.of(rejectedBy17H,candidate));
        when(proposalService.evaluateAutonomous(workspace,reviewId,rejectedBy17H.getId())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"NO_MATERIAL_OBSERVED_DIFFERENCE"));
        screenWith(rejectedBy17H);
        Evaluation result=service.dryRun(principal,robotId);
        assertThat(result.selectedCandidatePersonaId()).as("a candidate rejected by 17H is never reported as memory-suppressed").isEqualTo(candidate.getId());
        assertThat(result.memorySkippedCandidates()).isEmpty();

        List<Persona> many=new ArrayList<>();for(int i=0;i<AutonomousProposalService.CANDIDATE_LIMIT+1;i++)many.add(persona("Many "+i));
        when(personas.findByWorkspaceAndStatusOrderByIdAsc(eq(workspace),eq(PersonaStatus.ACTIVE),any(Pageable.class))).thenReturn(many);
        for(Persona c:many)when(proposalService.evaluateAutonomous(workspace,reviewId,c.getId())).thenReturn(evidence(c,Direction.LOWER_OBSERVED,"s"+c.getName()));
        screenWith(many.toArray(new Persona[0]));
        Evaluation bounded=service.dryRun(principal,robotId);
        assertThat(bounded.candidateCountConsidered()).isEqualTo(AutonomousProposalService.CANDIDATE_LIMIT);
        assertThat(bounded.memorySkippedCandidates()).hasSize(AutonomousProposalService.CANDIDATE_LIMIT);
        assertThat(bounded.reasons()).containsExactly(Reason.ALL_CANDIDATES_MEMORY_SUPPRESSED);
    }

    @Test void memoryNeverRanksTheFirstEligibleCanonicalCandidateWinsEvenIfALaterOneHasAFavorableHistory(){
        autoPropose();Persona second=persona("Second");validCandidates(candidate,second);
        com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Memory stableOld=new com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Memory(
                workspace.getId(),robotId,baseline.getId(),second.getId(),com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Outcome.OBSERVED_STABLE,
                1,1,0,0,NOW.minusSeconds(86400L*400),NOW.minusSeconds(86400L*400),null,null,NOW.minusSeconds(86400L*400),null,null,NOW,null,null,null,null,"READY_STABLE",3);
        Map<com.fdmultimedia.api.adaptivememory.AdaptiveMemoryProjector.Key,com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Memory> map=new HashMap<>();
        map.put(new com.fdmultimedia.api.adaptivememory.AdaptiveMemoryProjector.Key(robotId,baseline.getId(),second.getId()),stableOld);
        when(memory.screen(any(),any())).thenAnswer(i->new AdaptiveMemoryService.Screen(i.getArgument(0),i.getArgument(1),map,NOW));
        when(memory.screenReadOnly(any(),any())).thenAnswer(i->new AdaptiveMemoryService.Screen(i.getArgument(0),i.getArgument(1),map,NOW));
        assertThat(service.dryRun(principal,robotId).selectedCandidatePersonaId()).isEqualTo(candidate.getId());
    }

    @Test void opportunityDedupeStillAppliesAfterMemoryAndTheSkipListIsPreserved(){
        autoPropose();Persona second=persona("Second");validCandidates(candidate,second);screenWith(candidate);
        OptimizationProposal existing=mock(OptimizationProposal.class);when(existing.getId()).thenReturn(UUID.randomUUID());
        when(proposals.findByAutomationOpportunityFingerprint(anyString())).thenReturn(Optional.of(existing));
        Evaluation result=service.dryRun(principal,robotId);
        assertThat(result.reasons()).containsExactly(Reason.DUPLICATE_OPPORTUNITY);
        assertThat(result.memorySkippedCandidates()).hasSize(1);
    }

    @Test void currentlyActiveTargetIsNeverEvaluatedAsACandidate(){
        autoPropose();validCandidates(baseline,candidate);
        Evaluation result=service.dryRun(principal,robotId);
        verify(proposalService,never()).evaluateAutonomous(workspace,reviewId,baseline.getId());
        assertThat(result.selectedCandidatePersonaId()).isEqualTo(candidate.getId());
        assertThat(result.candidateCountConsidered()).isEqualTo(1);
    }

    @Test void manualOnlyRobotsNeverTouchMemoryScreening(){
        when(policies.findByWorkspaceAndRobotId(workspace,robotId)).thenReturn(Optional.empty());
        clearInvocations(memory);
        Evaluation result=service.dryRun(principal,robotId);
        assertThat(result.reasons()).startsWith(Reason.MANUAL_ONLY);
        verifyNoInteractions(memory);
    }

    private RobotAdaptivePolicy policy(boolean enabled,ProposalAutomationMode mode){return new RobotAdaptivePolicy(workspace,robotId,
            enabled,2,30,72,true,true,true,mode,owner,NOW);}
    private AutonomousEvidence evidence(Persona selected,Direction direction,String semantic){return new AutonomousEvidence(reviewId,
            baseline.getId(),baseline.getName(),selected.getId(),selected.getName(),com.fdmultimedia.api.analytics.DashboardQuery.Metric.TOTAL_INTERACTIONS,
            com.fdmultimedia.api.analytics.DashboardQuery.Window.H72,"TEST",direction,"evidence",semantic);}
    private Persona persona(String name){return new Persona(workspace,name,null,com.fdmultimedia.api.contentsuggestions.SuggestionLanguage.AUTO,
            com.fdmultimedia.api.contentsuggestions.SuggestionTone.NEUTRAL,"Audience","Voice",null,null,null,null,owner,NOW);}
}
