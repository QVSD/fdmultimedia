package com.fdmultimedia.api.analytics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fdmultimedia.api.analytics.CampaignPerformanceModels.*;
import com.fdmultimedia.api.analytics.CampaignPerformanceStore.EvidenceRow;
import com.fdmultimedia.api.analytics.DashboardQuery.*;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.campaigns.CampaignCopySetService;
import com.fdmultimedia.api.robots.*;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class CampaignPerformanceServiceTest {
    private final AuthService auth=mock(AuthService.class);
    private final RobotRunRepository runs=mock(RobotRunRepository.class);
    private final CampaignPerformanceReviewRepository reviews=mock(CampaignPerformanceReviewRepository.class);
    private final CampaignPerformanceReviewOutputRepository outputs=mock(CampaignPerformanceReviewOutputRepository.class);
    private final CampaignPerformanceRecommendationRepository recommendations=mock(CampaignPerformanceRecommendationRepository.class);
    private final CampaignPerformanceStore store=mock(CampaignPerformanceStore.class);
    private final PerformanceInsightProperties thresholds=new PerformanceInsightProperties();
    private final Clock clock=Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"),ZoneOffset.UTC);
    private final CampaignPerformanceService service=new CampaignPerformanceService(auth,runs,reviews,outputs,recommendations,store,thresholds,clock);
    private final AuthenticatedUser principal=mock(AuthenticatedUser.class);
    private final Workspace workspace=new Workspace("Test","test");
    private final AppUser user=new AppUser("user@example.test","hash","User");
    private final WorkspaceMembership membership=new WorkspaceMembership(workspace,user,WorkspaceRole.OWNER);
    private final RobotRun run=mock(RobotRun.class);
    private final UUID runId=UUID.randomUUID();
    private final AtomicReference<List<CampaignPerformanceReviewOutput>> persistedOutputs=new AtomicReference<>(List.of());
    private final AtomicReference<List<CampaignPerformanceRecommendation>> persistedRecommendations=new AtomicReference<>(new ArrayList<>());

    @BeforeEach void setUp(){
        when(auth.currentMembershipFor(principal)).thenReturn(membership);
        when(run.getId()).thenReturn(runId);when(run.getWorkspace()).thenReturn(workspace);when(run.isTerminal()).thenReturn(true);
        when(run.getStatus()).thenReturn(RobotRunStatus.SUCCEEDED);when(run.getRequestedOutputCount()).thenReturn(3);when(run.getActualOutputCount()).thenReturn(3);
        when(runs.findByIdForUpdate(runId)).thenReturn(Optional.of(run));when(reviews.maxRevision(any(),any())).thenReturn(0);
        when(reviews.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
        when(outputs.saveAll(any())).thenAnswer(i->{List<CampaignPerformanceReviewOutput> rows=i.getArgument(0);persistedOutputs.set(rows);return rows;});
        when(outputs.findByReviewOrderBySelectionOrderAscIdAsc(any())).thenAnswer(i->persistedOutputs.get());
        when(recommendations.save(any())).thenAnswer(i->{CampaignPerformanceRecommendation r=i.getArgument(0);persistedRecommendations.get().add(r);return r;});
        when(recommendations.findByReviewOrderBySequenceAsc(any())).thenAnswer(i->persistedRecommendations.get());
    }

    @Test void preservesObservedZeroAndDoesNotImputeNull(){
        when(store.evidence(any(),eq(runId),eq(Window.H72),any())).thenReturn(List.of(
                row(1,OutputEvidenceStatus.OBSERVED,0L),row(2,OutputEvidenceStatus.OBSERVED,null),row(3,OutputEvidenceStatus.MISSING_SNAPSHOT,null)));
        Review review=service.create(principal,runId,new CampaignPerformanceService.CreateRequest("H72","VIEWS"));
        assertThat(review.metrics().get(Metric.VIEWS).sampleCount()).isOne();
        assertThat(review.metrics().get(Metric.VIEWS).total()).isEqualByComparingTo("0");
        assertThat(review.outputs()).extracting(o->o.metrics().get(Metric.VIEWS)).containsExactly(0L,null,null);
        assertThat(review.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_SAMPLE);
    }

    @Test void tooYoungHasPriorityAndProducesEvidenceBackedWaitRecommendation(){
        when(store.evidence(any(),eq(runId),eq(Window.D7),any())).thenReturn(List.of(
                row(1,OutputEvidenceStatus.TOO_YOUNG,null),row(2,OutputEvidenceStatus.TOO_YOUNG,null),row(3,OutputEvidenceStatus.TOO_YOUNG,null)));
        Review review=service.create(principal,runId,new CampaignPerformanceService.CreateRequest("D7","TOTAL_INTERACTIONS"));
        assertThat(review.evidenceStatus()).isEqualTo(EvidenceStatus.TOO_YOUNG);
        assertThat(review.recommendations()).extracting(Recommendation::type)
                .containsExactly(RecommendationType.WAIT_FOR_OBSERVATION_WINDOW);
        assertThat(review.recommendations().get(0).evidence()).containsEntry("sampleCount",0L).containsEntry("observationWindow","D7");
    }

    @Test void rejectsLatestAndNonTerminalRuns(){
        assertThatThrownBy(()->service.create(principal,runId,new CampaignPerformanceService.CreateRequest("LATEST","VIEWS")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("H24");
        when(run.isTerminal()).thenReturn(false);
        assertThatThrownBy(()->service.create(principal,runId,new CampaignPerformanceService.CreateRequest("H24","VIEWS")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("terminal");
    }

    @Test void controlledExperimentRecommendationRequiresTwoMatureCoveredCohorts(){
        when(store.cohorts(any(),any(),any(),eq(Window.H72),eq(Metric.VIEWS),eq(CampaignPerformanceModels.Dimension.ROLE),isNull(),any()))
                .thenReturn(new CampaignPerformanceStore.CohortRows(List.of(
                        cohort("INTRODUCTION",100,10),cohort("CONCLUSION",70,10)),false));
        CohortComparison result=service.cohorts(principal,new CampaignPerformanceService.CohortRequest(
                "2026-09-01","2026-09-30","H72","VIEWS","ROLE",null));
        assertThat(result.recommendations()).extracting(Recommendation::type)
                .containsExactly(RecommendationType.CONSIDER_CONTROLLED_EXPERIMENT);
        assertThat(result.recommendations().get(0).message()).contains("controlled experiment").doesNotContain("change Robot");
    }

    @Test void partialCampaignNeverImputesZeroForAFailedOrMissingOutput(){
        when(store.evidence(any(),eq(runId),eq(Window.H72),any())).thenReturn(List.of(
                row(1,OutputEvidenceStatus.OBSERVED,120L),
                failedRow(2),
                row(3,OutputEvidenceStatus.OBSERVED,80L)));
        when(run.getRequestedOutputCount()).thenReturn(3);when(run.getActualOutputCount()).thenReturn(3);
        Review review=service.create(principal,runId,new CampaignPerformanceService.CreateRequest("H72","VIEWS"));
        assertThat(review.failedOutputCount()).isOne();
        assertThat(review.publishedOutputCount()).isEqualTo(2);
        assertThat(review.outputs()).extracting(o->o.metrics().get(Metric.VIEWS)).containsExactly(120L,null,80L);
        assertThat(review.metrics().get(Metric.VIEWS).sampleCount()).isEqualTo(2);
    }

    @Test void withinCampaignComparisonUsesNeutralDirectionAndNeverAssertsCausation(){
        when(store.evidence(any(),eq(runId),eq(Window.H72),any())).thenReturn(List.of(
                row(1,OutputEvidenceStatus.OBSERVED,200L),row(2,OutputEvidenceStatus.OBSERVED,50L),row(3,OutputEvidenceStatus.OBSERVED,52L)));
        Review review=service.create(principal,runId,new CampaignPerformanceService.CreateRequest("H72","VIEWS"));
        assertThat(review.comparisons()).hasSize(3);
        ObservedComparison first=review.comparisons().stream().filter(c->c.leftSelectionOrder()==1&&c.rightSelectionOrder()==2).findFirst().orElseThrow();
        assertThat(first.direction()).isEqualTo(Direction.HIGHER_OBSERVED);
        assertThat(first.message()).doesNotContainIgnoringCase("caus").doesNotContainIgnoringCase("because");
        ObservedComparison similar=review.comparisons().stream().filter(c->c.leftSelectionOrder()==2&&c.rightSelectionOrder()==3).findFirst().orElseThrow();
        assertThat(similar.direction()).isEqualTo(Direction.SIMILAR_OBSERVED);
    }

    @Test void coordinationPolicyDimensionAggregatesDescriptivelyWithoutClaimingSuperiority(){
        when(store.cohorts(any(),any(),any(),eq(Window.H72),eq(Metric.VIEWS),eq(CampaignPerformanceModels.Dimension.COORDINATION_POLICY),isNull(),any()))
                .thenReturn(new CampaignPerformanceStore.CohortRows(List.of(
                        cohort("INDEPENDENT_COPY",100,10),cohort("COORDINATED_COPY_FOR_REVIEW",90,10)),false));
        CohortComparison result=service.cohorts(principal,new CampaignPerformanceService.CohortRequest(
                "2026-09-01","2026-09-30","H72","VIEWS","COORDINATION_POLICY",null));
        assertThat(result.rows()).extracting(CohortRow::key).containsExactlyInAnyOrder("INDEPENDENT_COPY","COORDINATED_COPY_FOR_REVIEW");
        assertThat(result.limitations()).anyMatch(l->l.toLowerCase(Locale.ROOT).contains("coordination-policy cohorts are observational"));
    }

    @Test void providerLimitationIsAlwaysDisclosedForTestProviderCohorts(){
        when(store.cohorts(any(),any(),any(),any(),any(),any(),isNull(),any()))
                .thenReturn(new CampaignPerformanceStore.CohortRows(List.of(),false));
        CohortComparison result=service.cohorts(principal,new CampaignPerformanceService.CohortRequest(
                "2026-09-01","2026-09-30","H72","VIEWS","ROLE",null));
        assertThat(result.limitations()).anyMatch(l->l.contains("TEST publishing and analytics are deterministic synthetic data"));
    }

    @Test void recommendationPriorityGivesTooYoungPrecedenceOverInsufficientSample(){
        when(store.evidence(any(),eq(runId),eq(Window.H24),any())).thenReturn(List.of(
                row(1,OutputEvidenceStatus.TOO_YOUNG,null),row(2,OutputEvidenceStatus.TOO_YOUNG,null)));
        Review review=service.create(principal,runId,new CampaignPerformanceService.CreateRequest("H24","VIEWS"));
        assertThat(review.evidenceStatus()).isEqualTo(EvidenceStatus.TOO_YOUNG);
        assertThat(review.recommendations()).extracting(Recommendation::type).containsExactly(RecommendationType.WAIT_FOR_OBSERVATION_WINDOW);
    }

    @Test void noControlledExperimentRecommendationBelowTheTwoCohortMaturityGate(){
        when(store.cohorts(any(),any(),any(),eq(Window.H72),eq(Metric.VIEWS),eq(CampaignPerformanceModels.Dimension.ROLE),isNull(),any()))
                .thenReturn(new CampaignPerformanceStore.CohortRows(List.of(cohort("INTRODUCTION",100,10)),false));
        CohortComparison result=service.cohorts(principal,new CampaignPerformanceService.CohortRequest(
                "2026-09-01","2026-09-30","H72","VIEWS","ROLE",null));
        assertThat(result.recommendations()).isEmpty();
    }

    @Test void legacyRunWithNoRobotRunOutputsProducesABoundedRepresentationRatherThanCrashing(){
        UUID legacyPublication=UUID.randomUUID();
        when(store.evidence(any(),eq(runId),eq(Window.H72),any())).thenReturn(List.of(
                new EvidenceRow(null,null,null,"SUCCEEDED",null,null,null,null,null,null,null,null,
                        null,null,null,legacyPublication,"TEST",Instant.parse("2026-09-01T00:00:00Z"),UUID.randomUUID(),
                        OutputEvidenceStatus.OBSERVED,10L,null,null,null,null,null,null)));
        when(run.getRequestedOutputCount()).thenReturn(1);when(run.getActualOutputCount()).thenReturn(null);
        Review review=service.create(principal,runId,new CampaignPerformanceService.CreateRequest("H72","VIEWS"));
        assertThat(review.outputs()).hasSize(1);
        assertThat(review.outputs().get(0).robotRunOutputId()).isNull();
        assertThat(review.outputs().get(0).campaignRole()).isNull();
    }

    @Test void getRejectsAReviewFromAnotherWorkspace(){
        Workspace otherWorkspace=new Workspace("Other","other");
        when(reviews.findByWorkspaceAndId(eq(otherWorkspace),any())).thenReturn(Optional.empty());
        WorkspaceMembership otherMembership=new WorkspaceMembership(otherWorkspace,user,WorkspaceRole.OWNER);
        AuthenticatedUser otherPrincipal=mock(AuthenticatedUser.class);
        when(auth.currentMembershipFor(otherPrincipal)).thenReturn(otherMembership);
        assertThatThrownBy(()->service.get(otherPrincipal,UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test void dependencyDirectionDoesNotFeedCampaignPerformanceBackIntoCopyGeneration(){
        assertThat(Arrays.stream(CampaignCopySetService.class.getDeclaredFields()).map(f->f.getType().getName()))
                .noneMatch(name->name.contains("CampaignPerformance")||name.contains("PublicationAnalytics")||name.contains("PerformanceInsight"));
        assertThat(Arrays.stream(CampaignPerformanceService.class.getDeclaredFields()).map(f->f.getType().getSimpleName()))
                .noneMatch(name->name.equals("RobotRepository")||name.equals("PersonaRepository"));
    }

    private EvidenceRow row(int order,OutputEvidenceStatus status,Long views){
        UUID outputId=UUID.randomUUID(); UUID publication=status==OutputEvidenceStatus.UNPUBLISHED?null:UUID.randomUUID();
        UUID snapshot=status==OutputEvidenceStatus.OBSERVED?UUID.randomUUID():null;
        return new EvidenceRow(outputId,order,order,"SUCCEEDED",order==1?"INTRODUCTION":"DEEP_DIVE",UUID.randomUUID(),
                UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),publication,"TEST",publication==null?null:Instant.parse("2026-09-20T12:00:00Z"),snapshot,status,
                views,null,status==OutputEvidenceStatus.OBSERVED?0L:null,status==OutputEvidenceStatus.OBSERVED?0L:null,
                status==OutputEvidenceStatus.OBSERVED?0L:null,null,status==OutputEvidenceStatus.OBSERVED?0L:null);
    }

    private EvidenceRow failedRow(int order){
        return new EvidenceRow(UUID.randomUUID(),order,order,"FAILED","DEEP_DIVE",UUID.randomUUID(),
                UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),null,null,null,
                null,null,null,null,OutputEvidenceStatus.UNPUBLISHED,null,null,null,null,null,null,null);
    }

    private static CohortRow cohort(String key,long median,long sample){
        return new CohortRow(key,key,sample,sample,sample,sample,java.math.BigDecimal.ONE,
                new MetricStatistics(java.math.BigDecimal.valueOf(median*sample),java.math.BigDecimal.valueOf(median),
                        java.math.BigDecimal.valueOf(median),java.math.BigDecimal.valueOf(median),java.math.BigDecimal.valueOf(median),sample));
    }
}
