package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.CampaignPerformanceModels.*;
import com.fdmultimedia.api.analytics.CampaignPerformanceStore.EvidenceRow;
import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.robots.*;
import com.fdmultimedia.api.workspaces.Workspace;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class CampaignPerformanceService {
    public static final String ENGINE_VERSION="CAMPAIGN_PERFORMANCE_V1";
    public static final String RECOMMENDATION_VERSION="CAMPAIGN_RECOMMENDATIONS_V1";
    public static final String DISCLAIMER="Observed associations do not establish causation. Controlled experiments are required for stronger causal conclusions.";
    public static final String TEST_LIMITATION="TEST publishing and analytics are deterministic synthetic data, not representative of real social-platform engagement.";

    private final AuthService auth;
    private final RobotRunRepository runs;
    private final CampaignPerformanceReviewRepository reviews;
    private final CampaignPerformanceReviewOutputRepository outputs;
    private final CampaignPerformanceRecommendationRepository recommendations;
    private final CampaignPerformanceStore store;
    private final PerformanceInsightProperties thresholds;
    private final Clock clock;
    @Autowired(required=false) private ApplicationEventPublisher events;

    public CampaignPerformanceService(AuthService auth,RobotRunRepository runs,CampaignPerformanceReviewRepository reviews,
            CampaignPerformanceReviewOutputRepository outputs,CampaignPerformanceRecommendationRepository recommendations,
            CampaignPerformanceStore store,PerformanceInsightProperties thresholds,Clock clock){
        this.auth=auth;this.runs=runs;this.reviews=reviews;this.outputs=outputs;this.recommendations=recommendations;
        this.store=store;this.thresholds=thresholds;this.clock=clock;
    }

    @Transactional
    public Review create(AuthenticatedUser principal,UUID runId,CreateRequest request){
        var membership=auth.currentMembershipFor(principal); Workspace workspace=membership.getWorkspace();
        RobotRun run=runs.findByIdForUpdate(runId).filter(r->r.getWorkspace().getId().equals(workspace.getId()))
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Robot run not found"));
        if(!run.isTerminal()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Campaign must be terminal before performance review");
        Window window=parseWindow(request==null?null:request.observationWindow());
        Metric metric=parseMetric(request==null?null:request.metric());
        Instant now=Instant.now(clock);
        List<EvidenceRow> evidence=store.evidence(workspace.getId(),runId,window,now);
        Counts counts=counts(run,evidence);
        MetricStatistics primary=statistics(evidence,metric);
        EvidenceStatus status=classify(counts,primary);
        int revision=reviews.maxRevision(run,window)+1;
        CampaignPerformanceReview review=new CampaignPerformanceReview(run,revision,window,metric,ENGINE_VERSION,
                RECOMMENDATION_VERSION,status,counts.intended(),counts.actual(),counts.published(),counts.failed(),
                counts.eligible(),counts.analytics(),now,membership.getUser(),now);
        try { reviews.saveAndFlush(review); }
        catch(DataIntegrityViolationException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"A review revision was created concurrently");}
        outputs.saveAll(evidence.stream().map(row->new CampaignPerformanceReviewOutput(review,row,now)).toList());
        List<RecommendationDraft> recommendationDrafts=recommend(status,metric,counts,primary,evidence,window);
        int sequence=1;
        for(RecommendationDraft draft:recommendationDrafts){
            recommendations.save(new CampaignPerformanceRecommendation(review,sequence++,draft.type(),metric,draft.dimension(),
                    draft.evidence(),draft.message(),limitations(evidence),now));
        }
        if(events!=null)events.publishEvent(new CampaignPerformanceReviewCreatedEvent(review.getId(),run.getRobot().getId()));
        return detail(review);
    }

    @Transactional(readOnly=true)
    public List<ReviewListItem> list(AuthenticatedUser principal,UUID runId,int limit){
        Workspace workspace=auth.currentMembershipFor(principal).getWorkspace();
        RobotRun run=runs.findByWorkspaceAndId(workspace,runId)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Robot run not found"));
        return reviews.findByRobotRunOrderByCreatedAtDesc(run,PageRequest.of(0,bound(limit,1,50))).stream().map(r->new ReviewListItem(
                r.getId(),runId,r.getRevision(),r.getObservationWindow(),r.getPrimaryMetric(),r.getEvidenceStatus(),
                r.getPublishedOutputCount(),r.getAnalyticsPublicationCount(),r.getCreatedAt())).toList();
    }

    @Transactional(readOnly=true)
    public Review get(AuthenticatedUser principal,UUID reviewId){
        Workspace workspace=auth.currentMembershipFor(principal).getWorkspace();
        return detail(reviews.findByWorkspaceAndId(workspace,reviewId)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Campaign performance review not found")));
    }

    @Transactional(readOnly=true)
    public List<CampaignOption> options(AuthenticatedUser principal,int limit){
        UUID workspace=auth.currentMembershipFor(principal).getWorkspace().getId();
        return store.campaignOptions(workspace,bound(limit,1,100));
    }

    @Transactional(readOnly=true)
    public CohortComparison cohorts(AuthenticatedUser principal,CohortRequest request){
        UUID workspace=auth.currentMembershipFor(principal).getWorkspace().getId();
        LocalDate today=LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from=parseDate(request.dateFrom(),today.minusDays(89));
        LocalDate to=parseDate(request.dateTo(),today);
        if(from.isAfter(to)||to.isAfter(today)||ChronoUnit.DAYS.between(from,to)>364)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Campaign comparison range must be within 365 days and not in the future");
        Window window=parseWindow(request.observationWindow()); Metric metric=parseMetric(request.metric());
        Dimension dimension=parseDimension(request.dimension()); String provider=parseProvider(request.provider());
        var result=store.cohorts(workspace,from,to,window,metric,dimension,provider,Instant.now(clock));
        List<String> limits=new ArrayList<>(List.of(DISCLAIMER));
        if(provider==null||provider.equals("TEST")) limits.add(TEST_LIMITATION);
        if(dimension==Dimension.COORDINATION_POLICY) limits.add("Coordination-policy cohorts are observational and may differ in content, Persona, timing, and provider mix.");
        List<Recommendation> recs=cohortRecommendations(result.rows(),metric,dimension,limits);
        return new CohortComparison(from,to,window,metric,dimension,result.rows(),result.truncated(),recs,limits);
    }

    private Review detail(CampaignPerformanceReview review){
        List<CampaignPerformanceReviewOutput> rows=outputs.findByReviewOrderBySelectionOrderAscIdAsc(review);
        List<OutputEvidence> evidence=rows.stream().map(this::toEvidence).toList();
        Map<Metric,MetricStatistics> stats=new EnumMap<>(Metric.class);
        for(Metric metric:Metric.values()) stats.put(metric,statisticsPersisted(rows,metric));
        List<Recommendation> recs=recommendations.findByReviewOrderBySequenceAsc(review).stream().map(r->new Recommendation(
                r.getId(),r.getSequence(),r.getType(),r.getMetric(),r.getComparedDimension(),r.getEvidence(),r.getMessage(),r.getLimitations())).toList();
        return new Review(review.getId(),review.getRobotRun().getId(),review.getRevision(),review.getObservationWindow(),
                review.getPrimaryMetric(),review.getEngineVersion(),review.getRecommendationEngineVersion(),review.getEvidenceStatus(),
                review.getRobotRunStatusSnapshot(),review.getIntendedOutputCount(),review.getActualOutputCount(),review.getPublishedOutputCount(),
                review.getFailedOutputCount(),review.getEligibleByAgeCount(),review.getAnalyticsPublicationCount(),review.getEvidenceCutoffAt(),
                review.getCreatedAt(),stats,evidence,comparisons(evidence,review.getPrimaryMetric()),recs,limitations(evidence));
    }

    private OutputEvidence toEvidence(CampaignPerformanceReviewOutput row){
        Map<Metric,Long> metrics=new EnumMap<>(Metric.class);
        metrics.put(Metric.VIEWS,row.getViews());metrics.put(Metric.REACH,row.getReach());metrics.put(Metric.LIKES,row.getLikes());
        metrics.put(Metric.COMMENTS,row.getComments());metrics.put(Metric.SHARES,row.getShares());metrics.put(Metric.SAVES,row.getSaves());
        metrics.put(Metric.TOTAL_INTERACTIONS,row.getTotalInteractions());
        return new OutputEvidence(row.getId(),row.getRobotRunOutputId(),row.getSelectionOrder(),row.getSourceRank(),row.getOutputStatus(),
                row.getCampaignRole(),row.getHighlightCandidateId(),row.getCampaignPlanId(),row.getCampaignPlanRevision(),row.getCampaignPlanItemId(),
                row.getCampaignCopySetId(),row.getCampaignCopySetRevision(),row.getCampaignCopyItemId(),row.getContentSuggestionId(),row.getContentDraftId(),
                row.getPublishScheduleId(),row.getPublicationId(),row.getProvider(),row.getPublishedAt(),row.getAnalyticsSnapshotId(),row.getEvidenceStatus(),metrics);
    }

    private Counts counts(RobotRun run,List<EvidenceRow> rows){
        int published=(int)rows.stream().filter(r->r.publicationId()!=null).count();
        int failed=(int)rows.stream().filter(r->"FAILED".equals(r.outputStatus())).count();
        int eligible=(int)rows.stream().filter(r->r.evidenceStatus()==OutputEvidenceStatus.OBSERVED||r.evidenceStatus()==OutputEvidenceStatus.MISSING_SNAPSHOT).count();
        int analytics=(int)rows.stream().filter(r->r.analyticsSnapshotId()!=null).count();
        int actual=run.getActualOutputCount()==null?(int)rows.stream().filter(r->r.robotRunOutputId()!=null).count():run.getActualOutputCount();
        return new Counts(run.getRequestedOutputCount(),actual,published,failed,eligible,analytics);
    }

    private EvidenceStatus classify(Counts counts,MetricStatistics metric){
        if(counts.published()>0&&counts.eligible()==0)return EvidenceStatus.TOO_YOUNG;
        if(counts.analytics()>=thresholds.getMinSampleSize()&&metric.sampleCount()==0)return EvidenceStatus.METRIC_UNAVAILABLE;
        if(metric.sampleCount()<thresholds.getMinSampleSize())return EvidenceStatus.INSUFFICIENT_SAMPLE;
        BigDecimal coverage=coverage(metric.sampleCount(),counts.eligible());
        if(coverage==null||coverage.compareTo(thresholds.getMinCoverage())<0)return EvidenceStatus.LOW_COVERAGE;
        return EvidenceStatus.READY;
    }

    private List<RecommendationDraft> recommend(EvidenceStatus status,Metric metric,Counts counts,MetricStatistics statistics,
            List<EvidenceRow> rows,Window window){
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("metric",metric.name());evidence.put("observationWindow",window.name());
        evidence.put("sampleCount",statistics.sampleCount());evidence.put("eligibleByAgeCount",counts.eligible());
        evidence.put("coverage",coverage(statistics.sampleCount(),counts.eligible()));
        return switch(status){
            case TOO_YOUNG->List.of(new RecommendationDraft(RecommendationType.WAIT_FOR_OBSERVATION_WINDOW,null,evidence,
                    "Wait for the campaign publications to reach the selected observation window before reviewing differences."));
            case METRIC_UNAVAILABLE,LOW_COVERAGE->List.of(new RecommendationDraft(RecommendationType.CHECK_ANALYTICS_COVERAGE,null,evidence,
                    "Check provider metric availability and analytics collection coverage before interpreting this campaign."));
            case INSUFFICIENT_SAMPLE->List.of(new RecommendationDraft(RecommendationType.COLLECT_MORE_DATA,null,evidence,
                    "Collect more comparable observations before interpreting differences as a repeatable pattern."));
            case READY->hasObservedDifference(rows,metric)?List.of(
                    new RecommendationDraft(RecommendationType.REVIEW_OUTPUT_DIFFERENCES,"OUTPUT",evidence,
                            "Review the observed output differences alongside their fixed roles and copy provenance; the evidence does not identify a cause."))
                    :List.of();
        };
    }

    private List<Recommendation> cohortRecommendations(List<CohortRow> rows,Metric metric,Dimension dimension,List<String> limits){
        if(rows.size()<2)return List.of();
        List<CohortRow> ready=rows.stream().filter(r->r.sampleCount()>=thresholds.getMinSampleSize()&&r.coverage()!=null
                &&r.coverage().compareTo(thresholds.getMinCoverage())>=0&&r.metric().median()!=null).toList();
        if(ready.size()<2)return List.of();
        BigDecimal min=ready.stream().map(r->r.metric().median()).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max=ready.stream().map(r->r.metric().median()).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        boolean material=min.signum()==0?max.signum()!=0:max.subtract(min).abs().divide(min.abs(),6,RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).compareTo(thresholds.getMaterialDifferencePercent())>=0;
        if(!material)return List.of();
        Map<String,Object> ev=new LinkedHashMap<>();ev.put("metric",metric.name());ev.put("dimension",dimension.name());
        ev.put("minimumMedian",min);ev.put("maximumMedian",max);ev.put("minimumSamplePerSegment",thresholds.getMinSampleSize());
        return List.of(new Recommendation(UUID.nameUUIDFromBytes((dimension+"|"+metric+"|"+min+"|"+max).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                1,RecommendationType.CONSIDER_CONTROLLED_EXPERIMENT,metric,dimension.name(),ev,
                "Observed cohort differences meet the descriptive evidence gate; consider a separately designed controlled experiment before making configuration changes.",limits));
    }

    private List<ObservedComparison> comparisons(List<OutputEvidence> rows,Metric metric){
        List<ObservedComparison> result=new ArrayList<>();
        for(int i=0;i<rows.size();i++)for(int j=i+1;j<rows.size();j++){
            OutputEvidence a=rows.get(i),b=rows.get(j);Long av=a.metrics().get(metric),bv=b.metrics().get(metric);
            Direction direction=av==null||bv==null?Direction.INSUFFICIENT_EVIDENCE:direction(av,bv);
            String message=direction==Direction.INSUFFICIENT_EVIDENCE
                    ?"These outputs do not both have an observed "+metric.name().toLowerCase(Locale.ROOT)+" value at this window."
                    :"Output "+label(a)+" had "+phrase(direction)+" "+metric.name().toLowerCase(Locale.ROOT)+" than output "+label(b)+" ("+av+" vs "+bv+").";
            result.add(new ObservedComparison(a.robotRunOutputId(),a.selectionOrder(),b.robotRunOutputId(),b.selectionOrder(),metric,av,bv,direction,message));
        }
        return result;
    }

    private Direction direction(long left,long right){
        if(left==right)return Direction.SIMILAR_OBSERVED;
        BigDecimal denominator=BigDecimal.valueOf(Math.abs(right));
        boolean material=denominator.signum()==0||BigDecimal.valueOf(Math.abs(left-right)).divide(denominator,6,RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).compareTo(thresholds.getMaterialDifferencePercent())>=0;
        if(!material)return Direction.SIMILAR_OBSERVED;
        return left>right?Direction.HIGHER_OBSERVED:Direction.LOWER_OBSERVED;
    }
    private static String phrase(Direction d){return switch(d){case HIGHER_OBSERVED->"a higher observed";case LOWER_OBSERVED->"a lower observed";case SIMILAR_OBSERVED->"a similar observed";case INSUFFICIENT_EVIDENCE->"insufficient observed";};}
    private static String label(OutputEvidence e){return e.selectionOrder()==null?"legacy":e.selectionOrder().toString();}
    private boolean hasObservedDifference(List<EvidenceRow> rows,Metric metric){List<Long> values=rows.stream().map(r->r.metric(metric)).filter(Objects::nonNull).toList();return values.size()>1&&!Collections.min(values).equals(Collections.max(values));}

    private static MetricStatistics statistics(List<EvidenceRow> rows,Metric metric){return statisticsValues(rows.stream().map(r->r.metric(metric)).filter(Objects::nonNull).toList());}
    private static MetricStatistics statisticsPersisted(List<CampaignPerformanceReviewOutput> rows,Metric metric){
        return statisticsValues(rows.stream().map(r->switch(metric){case VIEWS->r.getViews();case REACH->r.getReach();case LIKES->r.getLikes();case COMMENTS->r.getComments();case SHARES->r.getShares();case SAVES->r.getSaves();case TOTAL_INTERACTIONS->r.getTotalInteractions();}).filter(Objects::nonNull).toList());
    }
    private static MetricStatistics statisticsValues(List<Long> raw){
        if(raw.isEmpty())return new MetricStatistics(null,null,null,null,null,0);
        List<Long> values=raw.stream().sorted().toList();BigDecimal total=values.stream().map(BigDecimal::valueOf).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal average=total.divide(BigDecimal.valueOf(values.size()),4,RoundingMode.HALF_UP).stripTrailingZeros();
        int middle=values.size()/2;BigDecimal median=values.size()%2==1?BigDecimal.valueOf(values.get(middle))
                :BigDecimal.valueOf(values.get(middle-1)).add(BigDecimal.valueOf(values.get(middle))).divide(BigDecimal.valueOf(2));
        return new MetricStatistics(total,average,median,BigDecimal.valueOf(values.get(0)),BigDecimal.valueOf(values.get(values.size()-1)),values.size());
    }
    private static BigDecimal coverage(long sample,int eligible){return eligible==0?null:BigDecimal.valueOf(sample).divide(BigDecimal.valueOf(eligible),4,RoundingMode.HALF_UP);}
    private static List<String> limitations(List<?> rows){
        List<String> result=new ArrayList<>();result.add(DISCLAIMER);
        boolean test=rows.stream().anyMatch(row->row instanceof EvidenceRow source&&"TEST".equals(source.provider())
                ||row instanceof OutputEvidence persisted&&"TEST".equals(persisted.provider()));
        if(test)result.add(TEST_LIMITATION);result.add("Missing analytics reduce coverage and are never imputed as zero.");return result;
    }
    private static Window parseWindow(String value){try{Window w=Window.valueOf(value==null?"H72":value);if(w==Window.LATEST)throw new IllegalArgumentException();return w;}catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Campaign reviews support H24, H72, or D7");}}
    private static Metric parseMetric(String value){try{return Metric.valueOf(value==null?"TOTAL_INTERACTIONS":value);}catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid campaign metric");}}
    private static Dimension parseDimension(String value){try{return Dimension.valueOf(value==null?"ROLE":value);}catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid campaign comparison dimension");}}
    private static String parseProvider(String value){if(value==null||value.isBlank())return null;if(value.equals("TEST")||value.equals("INSTAGRAM"))return value;throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported analytics provider");}
    private static LocalDate parseDate(String value,LocalDate fallback){try{return value==null||value.isBlank()?fallback:LocalDate.parse(value);}catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid campaign comparison date");}}
    private static int bound(int value,int min,int max){return Math.max(min,Math.min(max,value));}

    public record CreateRequest(String observationWindow,String metric){}
    public record CohortRequest(String dateFrom,String dateTo,String observationWindow,String metric,String dimension,String provider){}
    private record Counts(int intended,int actual,int published,int failed,int eligible,int analytics){}
    private record RecommendationDraft(RecommendationType type,String dimension,Map<String,Object> evidence,String message){}
}
