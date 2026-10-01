package com.fdmultimedia.api.optimization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fdmultimedia.api.analytics.*;
import com.fdmultimedia.api.analytics.CampaignPerformanceModels.EvidenceStatus;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentsuggestions.*;
import com.fdmultimedia.api.experiments.*;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.*;
import com.fdmultimedia.api.optimization.OptimizationProposalStore.*;
import com.fdmultimedia.api.personas.*;
import com.fdmultimedia.api.robots.*;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class OptimizationProposalServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-01T00:00:00Z");
    private final AuthService auth=mock(AuthService.class);
    private final CampaignPerformanceReviewRepository reviews=mock(CampaignPerformanceReviewRepository.class);
    private final OptimizationProposalRepository proposals=mock(OptimizationProposalRepository.class);
    private final OptimizationProposalStore store=mock(OptimizationProposalStore.class);
    private final PersonaRepository personas=mock(PersonaRepository.class);
    private final PerformanceInsightProperties thresholds=new PerformanceInsightProperties();
    private final ExperimentService experiments=mock(ExperimentService.class);
    private final OptimizationProposalService service=new OptimizationProposalService(auth,reviews,proposals,store,personas,thresholds,experiments,Clock.fixed(NOW,ZoneOffset.UTC));
    private final Workspace workspace=new Workspace("Test","test");
    private final AppUser owner=new AppUser("owner@example.test","hash","Owner");
    private final AuthenticatedUser principal=new AuthenticatedUser(owner);
    private final Persona baseline=persona("Baseline");
    private final Persona candidate=persona("Candidate");
    private final CampaignPerformanceReview review=review();
    private final AtomicReference<OptimizationProposal> saved=new AtomicReference<>();

    @BeforeEach void setUp(){
        when(auth.currentMembershipFor(principal)).thenReturn(new WorkspaceMembership(workspace,owner,WorkspaceRole.OWNER));
        when(reviews.findByWorkspaceAndId(workspace,review.getId())).thenReturn(Optional.of(review));
        when(store.reviewContext(workspace.getId(),review.getId())).thenReturn(new ReviewContext(baseline.getId(),baseline.getName(),"TEST"));
        when(personas.findByWorkspaceAndId(workspace,baseline.getId())).thenReturn(Optional.of(baseline));
        when(personas.findByWorkspaceAndId(workspace,candidate.getId())).thenReturn(Optional.of(candidate));
        when(proposals.maxRevision(any(),any(),any(),any(),any())).thenReturn(0);
        when(proposals.saveAndFlush(any())).thenAnswer(i->{OptimizationProposal p=i.getArgument(0);saved.set(p);return p;});
        cohorts(100,120,5,5,BigDecimal.ONE,BigDecimal.ONE);
    }

    @Test void createsPersonaOnlyProposalFromServerComputedComparableEvidence(){
        when(store.reviewContext(workspace.getId(),review.getId())).thenReturn(new ReviewContext(baseline.getId(),"Historical Baseline","TEST"));
        Summary result=service.create(principal,new CreateRequest(review.getId(),candidate.getId()));
        assertThat(result.factor()).isEqualTo("PERSONA");assertThat(result.statistic()).isEqualTo(Statistic.MEDIAN);
        assertThat(result.baselineSample()).isEqualTo(5);assertThat(result.candidateSample()).isEqualTo(5);
        assertThat(result.baselineValue()).isEqualByComparingTo("100");assertThat(result.candidateValue()).isEqualByComparingTo("120");
        assertThat(result.relativeDifferencePercent()).isEqualByComparingTo("20");
        assertThat(result.direction()).isEqualTo(Direction.HIGHER_OBSERVED);
        assertThat(result.baselinePersonaName()).isEqualTo("Historical Baseline");
        assertThat(result.rationale()).doesNotContainIgnoringCase("winner").doesNotContainIgnoringCase("best")
                .contains("Historical Baseline").doesNotContain("Persona Baseline").contains("not proof of causation");
        assertThat(result.limitation()).contains("observational").contains("TEST analytics");
    }

    @Test void eachCohortMustIndependentlyMeetSampleAndCoverageGates(){
        cohorts(100,120,5,4,BigDecimal.ONE,BigDecimal.ONE);
        assertCode("CANDIDATE_EVIDENCE_INSUFFICIENT_SAMPLE");
        cohorts(100,120,5,5,BigDecimal.ONE,new BigDecimal("0.59"));
        assertCode("CANDIDATE_EVIDENCE_LOW_COVERAGE");
    }

    @Test void materialDifferenceIsInclusiveAtTenPercentAndBelowThresholdIsRejected(){
        cohorts(100,110,5,5,BigDecimal.ONE,BigDecimal.ONE);
        assertThat(service.create(principal,new CreateRequest(review.getId(),candidate.getId())).relativeDifferencePercent()).isEqualByComparingTo("10");
        cohorts(100,109,5,5,BigDecimal.ONE,BigDecimal.ONE);
        assertCode("NO_MATERIAL_OBSERVED_DIFFERENCE");
    }

    @Test void zeroBaselineNeverDividesByZeroOrFabricatesRelativeMateriality(){
        cohorts(0,10,5,5,BigDecimal.ONE,BigDecimal.ONE);
        assertCode("NO_MATERIAL_OBSERVED_DIFFERENCE");
    }

    @Test void rejectsSameOrArchivedOrCrossWorkspaceCandidate(){
        assertThatThrownBy(()->service.create(principal,new CreateRequest(review.getId(),baseline.getId())))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("SAME_PERSONA");
        candidate.archive(NOW);
        assertCode("CANDIDATE_PERSONA_INACTIVE");
        when(personas.findByWorkspaceAndId(workspace,candidate.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.create(principal,new CreateRequest(review.getId(),candidate.getId())))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Persona not found");
    }

    @Test void sourceReviewMustBeMatureReadyEvidence(){
        CampaignPerformanceReview weak=review(EvidenceStatus.LOW_COVERAGE);
        when(reviews.findByWorkspaceAndId(workspace,weak.getId())).thenReturn(Optional.of(weak));
        assertThatThrownBy(()->service.create(principal,new CreateRequest(weak.getId(),candidate.getId())))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("SOURCE_REVIEW_NOT_READY");
    }

    @Test void approvalDoesNotCreateExperimentAndRejectionCannotMaterialize(){
        OptimizationProposal proposal=createEntity();when(proposals.findByWorkspaceAndIdForUpdate(workspace,proposal.getId())).thenReturn(Optional.of(proposal));
        Summary approved=service.approve(principal,proposal.getId());assertThat(approved.status()).isEqualTo(Status.APPROVED);
        verifyNoInteractions(experiments);
        OptimizationProposal rejected=createEntity();when(proposals.findByWorkspaceAndIdForUpdate(workspace,rejected.getId())).thenReturn(Optional.of(rejected));
        service.reject(principal,rejected.getId());
        assertThatThrownBy(()->service.materialize(principal,rejected.getId())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("NOT_APPROVED");
    }

    @Test void approvedProposalMaterializesExactlyOneDraftExperimentAndIsIdempotent(){
        OptimizationProposal proposal=createEntity();proposal.approve(NOW);
        when(proposals.findByWorkspaceAndIdForUpdate(workspace,proposal.getId())).thenReturn(Optional.of(proposal));
        UUID experimentId=UUID.randomUUID();when(experiments.createDraftFromOptimizationProposal(eq(principal),any(),eq(proposal.getId())))
                .thenReturn(new ExperimentSummary(experimentId,"test",null,"hypothesis",ExperimentFactor.PERSONA,ExperimentStatus.DRAFT,
                        AssignmentStrategy.DETERMINISTIC_BALANCED_V1,"H72","VIEWS",BigDecimal.ONE,List.of(),NOW,NOW,null,null));
        Summary first=service.materialize(principal,proposal.getId());Summary second=service.materialize(principal,proposal.getId());
        assertThat(first.status()).isEqualTo(Status.MATERIALIZED);assertThat(first.materializedExperimentId()).isEqualTo(experimentId);
        assertThat(second.materializedExperimentId()).isEqualTo(experimentId);
        verify(experiments,times(1)).createDraftFromOptimizationProposal(eq(principal),argThat(r->r.factor()==ExperimentFactor.PERSONA&&r.variantAPersonaId().equals(baseline.getId())&&r.variantBPersonaId().equals(candidate.getId())),eq(proposal.getId()));
    }

    @Test void personaMaterialChangeMakesApprovedProposalStaleBeforeExperimentCreation(){
        OptimizationProposal proposal=createEntity();proposal.approve(NOW);candidate.update("Changed",null,SuggestionLanguage.AUTO,SuggestionTone.NEUTRAL,"Audience","Voice",null,null,null,null,NOW.plusSeconds(1));
        when(proposals.findByWorkspaceAndIdForUpdate(workspace,proposal.getId())).thenReturn(Optional.of(proposal));
        assertThat(service.materialize(principal,proposal.getId()).status()).isEqualTo(Status.STALE);
        assertThat(proposal.getStatus()).isEqualTo(Status.STALE);verifyNoInteractions(experiments);
    }

    @Test void reevaluationCreatesANewRevisionAndKeepsThePreviousEvidenceHistorical(){
        OptimizationProposal first=createEntity();
        when(proposals.findBySourceReviewAndBaselinePersonaIdAndCandidatePersonaIdAndMetricAndStatisticAndCurrentTrue(
                review,baseline.getId(),candidate.getId(),DashboardQuery.Metric.VIEWS,Statistic.MEDIAN)).thenReturn(Optional.of(first));
        when(proposals.maxRevision(review,baseline.getId(),candidate.getId(),DashboardQuery.Metric.VIEWS,Statistic.MEDIAN)).thenReturn(1);
        Summary second=service.create(principal,new CreateRequest(review.getId(),candidate.getId()));
        assertThat(first.isCurrent()).isFalse();
        assertThat(second.revision()).isEqualTo(2);assertThat(second.current()).isTrue();
    }

    @Test void workspaceScopedReadsAndActionsDoNotRevealForeignProposals(){
        UUID foreignId=UUID.randomUUID();
        when(proposals.findByWorkspaceAndId(workspace,foreignId)).thenReturn(Optional.empty());
        when(proposals.findByWorkspaceAndIdForUpdate(workspace,foreignId)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.get(principal,foreignId)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("not found");
        assertThatThrownBy(()->service.approve(principal,foreignId)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("not found");
    }

    @Test void fingerprintsAreStableAndProposalLayerHasNoAiOrRobotMutationDependencies(){
        assertThat(OptimizationProposalService.personaFingerprint(baseline)).isEqualTo(OptimizationProposalService.personaFingerprint(baseline));
        assertThat(Arrays.stream(OptimizationProposalService.class.getDeclaredFields()).map(f->f.getType().getSimpleName()))
                .noneMatch(n->n.contains("AiProvider")||n.equals("RobotRepository")||n.equals("RobotService")||n.equals("CampaignPerformanceService"));
        assertThat(Arrays.stream(CampaignPerformanceService.class.getDeclaredFields()).map(f->f.getType().getSimpleName()))
                .noneMatch(n->n.contains("OptimizationProposal"));
    }

    private void assertCode(String code){assertThatThrownBy(()->service.create(principal,new CreateRequest(review.getId(),candidate.getId()))).isInstanceOf(ResponseStatusException.class).hasMessageContaining(code);}
    private void cohorts(long a,long b,int as,int bs,BigDecimal ac,BigDecimal bc){
        when(store.personaCohorts(eq(workspace.getId()),anySet(),eq("TEST"),eq(DashboardQuery.Window.H72),eq(DashboardQuery.Metric.VIEWS),any(),any()))
                .thenReturn(Map.of(baseline.getId(),new PersonaCohort(baseline.getId(),baseline.getName(),as,as,as,as,ac,BigDecimal.valueOf(a)),candidate.getId(),new PersonaCohort(candidate.getId(),candidate.getName(),bs,bs,bs,bs,bc,BigDecimal.valueOf(b))));
    }
    private OptimizationProposal createEntity(){service.create(principal,new CreateRequest(review.getId(),candidate.getId()));return saved.get();}
    private Persona persona(String name){return new Persona(workspace,name,null,SuggestionLanguage.AUTO,SuggestionTone.NEUTRAL,"Audience","Voice",null,null,null,null,owner,NOW);}
    private CampaignPerformanceReview review(){return review(EvidenceStatus.READY);}
    private CampaignPerformanceReview review(EvidenceStatus status){RobotRun run=mock(RobotRun.class);when(run.getWorkspace()).thenReturn(workspace);when(run.getStatus()).thenReturn(RobotRunStatus.SUCCEEDED);return new CampaignPerformanceReview(run,1,DashboardQuery.Window.H72,DashboardQuery.Metric.VIEWS,CampaignPerformanceService.ENGINE_VERSION,CampaignPerformanceService.RECOMMENDATION_VERSION,status,5,5,5,0,5,5,NOW,owner,NOW);}
}
