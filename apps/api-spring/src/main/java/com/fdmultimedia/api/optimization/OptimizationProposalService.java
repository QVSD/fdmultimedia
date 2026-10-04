package com.fdmultimedia.api.optimization;

import com.fdmultimedia.api.analytics.*;
import com.fdmultimedia.api.analytics.CampaignPerformanceModels.EvidenceStatus;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.experiments.*;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.*;
import com.fdmultimedia.api.optimization.OptimizationProposalStore.*;
import com.fdmultimedia.api.personas.*;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OptimizationProposalService {
    public static final String ENGINE_VERSION="OPTIMIZATION_PROPOSALS_V1";
    public static final String CONFOUNDING_LIMITATION="Historical Persona differences are observational and may be confounded by content, source, timing, provider, or other factors. A controlled experiment is required to test causality.";
    public static final String TEST_LIMITATION="TEST analytics are deterministic synthetic data and are not representative of real social-platform engagement.";
    private static final int MAX_LIST=100;
    private static final Duration COHORT_LOOKBACK=Duration.ofDays(365);

    private final AuthService auth;
    private final CampaignPerformanceReviewRepository reviews;
    private final OptimizationProposalRepository proposals;
    private final OptimizationProposalStore store;
    private final PersonaRepository personas;
    private final PerformanceInsightProperties thresholds;
    private final ExperimentService experiments;
    private final Clock clock;

    public OptimizationProposalService(AuthService auth,CampaignPerformanceReviewRepository reviews,
            OptimizationProposalRepository proposals,OptimizationProposalStore store,PersonaRepository personas,
            PerformanceInsightProperties thresholds,ExperimentService experiments,Clock clock){
        this.auth=auth;this.reviews=reviews;this.proposals=proposals;this.store=store;this.personas=personas;
        this.thresholds=thresholds;this.experiments=experiments;this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Eligibility eligibility(AuthenticatedUser principal,UUID reviewId){
        Workspace workspace=auth.currentMembershipFor(principal).getWorkspace();
        CampaignPerformanceReview review=requireReview(workspace,reviewId);
        ReviewContext context=context(workspace,review);
        boolean ready=review.getEvidenceStatus()==EvidenceStatus.READY&&context!=null;
        return new Eligibility(ready,ready?null:"SOURCE_REVIEW_NOT_READY",context==null?null:context.personaId(),
                context==null?null:context.personaName(),review.getPrimaryMetric(),review.getObservationWindow(),
                context==null?null:context.provider(),thresholds.getMinSampleSize(),thresholds.getMinCoverage(),
                thresholds.getMaterialDifferencePercent(),limitations(context==null?null:context.provider()));
    }

    @Transactional
    public Summary create(AuthenticatedUser principal,CreateRequest request){
        if(request==null||request.sourceReviewId()==null||request.candidatePersonaId()==null)
            throw bad("SOURCE_REVIEW_AND_CANDIDATE_REQUIRED");
        var membership=auth.currentMembershipFor(principal);Workspace workspace=membership.getWorkspace();
        Evaluated e=evaluate(workspace,request.sourceReviewId(),request.candidatePersonaId());
        UUID robotId=e.review().getRobotRun().getRobot().getId();
        return persist(e,membership.getUser(),Origin.MANUAL,null,robotId,null,"MANUAL");
    }

    @Transactional(readOnly=true,noRollbackFor=ResponseStatusException.class)
    public AutonomousEvidence evaluateAutonomous(Workspace workspace,UUID reviewId,UUID candidatePersonaId){
        Evaluated e=evaluate(workspace,reviewId,candidatePersonaId);
        return new AutonomousEvidence(e.review().getId(),e.baseline().getId(),nonBlank(e.context().personaName(),e.baseline().getName()),
                e.candidate().getId(),e.candidate().getName(),e.review().getPrimaryMetric(),e.review().getObservationWindow(),
                e.context().provider(),e.direction(),e.evidenceFingerprint(),e.semanticFingerprint());
    }

    @Transactional
    public Summary createAutonomous(Workspace workspace,AppUser actor,UUID reviewId,UUID candidatePersonaId,
            UUID robotId,int policyRevision,String trigger,String automationEngineVersion){
        Evaluated e=evaluate(workspace,reviewId,candidatePersonaId);
        if(!e.review().getRobotRun().getRobot().getId().equals(robotId))throw conflict("AUTOMATION_ROBOT_REVIEW_MISMATCH");
        return persist(e,actor,Origin.AUTO_PROPOSE,automationEngineVersion,robotId,policyRevision,trigger);
    }

    @Transactional(readOnly=true)
    public List<Summary> list(AuthenticatedUser principal,int limit,Origin origin){
        Workspace workspace=auth.currentMembershipFor(principal).getWorkspace();
        int bounded=Math.min(Math.max(limit,1),MAX_LIST);
        var rows=origin==null?proposals.findByWorkspaceOrderByCreatedAtDesc(workspace,PageRequest.of(0,bounded)):
                proposals.findByWorkspaceAndOriginOrderByCreatedAtDesc(workspace,origin,PageRequest.of(0,bounded));
        return rows.stream().map(this::summary).toList();
    }

    public List<Summary> list(AuthenticatedUser principal,int limit){return list(principal,limit,null);}

    @Transactional(readOnly=true)
    public Summary get(AuthenticatedUser principal,UUID id){return summary(require(auth.currentMembershipFor(principal).getWorkspace(),id));}

    @Transactional
    public Summary approve(AuthenticatedUser principal,UUID id){
        OptimizationProposal p=lock(principal,id);try{p.approve(Instant.now(clock));}catch(IllegalStateException e){throw conflict("PROPOSAL_NOT_REVIEWABLE");}
        return summary(p);
    }

    @Transactional
    public Summary reject(AuthenticatedUser principal,UUID id){
        OptimizationProposal p=lock(principal,id);try{p.reject(Instant.now(clock));}catch(IllegalStateException e){throw conflict("PROPOSAL_NOT_REVIEWABLE");}
        return summary(p);
    }

    @Transactional
    public Summary materialize(AuthenticatedUser principal,UUID id){
        OptimizationProposal p=lock(principal,id);
        if(p.getMaterializedExperimentId()!=null)return summary(p);
        if(p.getStatus()!=Status.APPROVED)throw conflict("PROPOSAL_NOT_APPROVED");
        Persona baseline=personas.findByWorkspaceAndId(p.getWorkspace(),p.getBaselinePersonaId()).orElse(null);
        Persona candidate=personas.findByWorkspaceAndId(p.getWorkspace(),p.getCandidatePersonaId()).orElse(null);
        if(!compatible(baseline,p.getBaselinePersonaFingerprint())||!compatible(candidate,p.getCandidatePersonaFingerprint())){
            p.markStale(Instant.now(clock));proposals.saveAndFlush(p);return summary(p);
        }
        String name="Persona test: "+bounded(p.getBaselinePersonaNameSnapshot()+" vs "+p.getCandidatePersonaNameSnapshot(),180);
        String hypothesis="Test whether the observed "+p.getMetric().name()+" difference persists under randomized PERSONA assignment at "+p.getObservationWindow().name()+". Observational evidence does not establish causation.";
        CreateExperimentRequest request=new CreateExperimentRequest(name,"Created from optimization proposal "+p.getId(),hypothesis,
                ExperimentFactor.PERSONA,p.getObservationWindow().name(),p.getMetric().name(),p.getBaselinePersonaId(),
                p.getBaselinePersonaNameSnapshot(),p.getCandidatePersonaId(),p.getCandidatePersonaNameSnapshot(),BigDecimal.ONE);
        ExperimentSummary experiment=experiments.createDraftFromOptimizationProposal(principal,request,p.getId());
        p.materialize(experiment.id(),Instant.now(clock));proposals.saveAndFlush(p);return summary(p);
    }

    private void requireCohort(PersonaCohort c,String side){if(c==null)throw conflict(side+"_EVIDENCE_INSUFFICIENT_SAMPLE");}
    private void requireEvidence(PersonaCohort c,String side){
        if(c.median()==null||c.sampleCount()<thresholds.getMinSampleSize())throw conflict(side+"_EVIDENCE_INSUFFICIENT_SAMPLE");
        if(c.coverage().compareTo(thresholds.getMinCoverage())<0)throw conflict(side+"_EVIDENCE_LOW_COVERAGE");
    }
    private Persona requireActivePersona(Workspace w,UUID id,String code){Persona p=personas.findByWorkspaceAndId(w,id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Persona not found"));if(p.getStatus()!=PersonaStatus.ACTIVE)throw conflict(code);return p;}
    private boolean compatible(Persona p,String fingerprint){return p!=null&&p.getStatus()==PersonaStatus.ACTIVE&&personaFingerprint(p).equals(fingerprint);}
    private CampaignPerformanceReview requireReview(Workspace w,UUID id){return reviews.findByWorkspaceAndId(w,id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Campaign performance review not found"));}
    private ReviewContext context(Workspace w,CampaignPerformanceReview r){try{return store.reviewContext(w.getId(),r.getId());}catch(IllegalStateException e){throw conflict(e.getMessage());}}
    private OptimizationProposal require(Workspace w,UUID id){return proposals.findByWorkspaceAndId(w,id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Optimization proposal not found"));}
    private OptimizationProposal lock(AuthenticatedUser principal,UUID id){Workspace w=auth.currentMembershipFor(principal).getWorkspace();return proposals.findByWorkspaceAndIdForUpdate(w,id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Optimization proposal not found"));}
    private List<String> limitations(String provider){List<String> list=new ArrayList<>();list.add(CONFOUNDING_LIMITATION);if("TEST".equals(provider))list.add(TEST_LIMITATION);return list;}
    private String rationale(String a,String b,CampaignPerformanceReview review,Direction direction){return "Persona "+b+" has a "+direction.name().toLowerCase(Locale.ROOT).replace('_',' ')+" median "+review.getPrimaryMetric().name()+" observation than Persona "+a+" in comparable "+review.getObservationWindow().name()+" evidence. This difference is a hypothesis to test, not proof of causation.";}
    private String fingerprint(CampaignPerformanceReview r,ReviewContext c,Persona a,Persona b,PersonaCohort ac,PersonaCohort bc,BigDecimal relative,String af,String bf){return sha256(String.join("|",r.getId().toString(),ENGINE_VERSION,"PERSONA",a.getId().toString(),af,b.getId().toString(),bf,r.getPrimaryMetric().name(),Statistic.MEDIAN.name(),r.getObservationWindow().name(),c.provider(),Integer.toString(ac.sampleCount()),ac.coverage().toPlainString(),ac.median().toPlainString(),Integer.toString(bc.sampleCount()),bc.coverage().toPlainString(),bc.median().toPlainString(),relative.toPlainString(),thresholds.getMinCoverage().toPlainString(),Integer.toString(thresholds.getMinSampleSize()),thresholds.getMaterialDifferencePercent().toPlainString()));}
    static String sha256(String value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static String personaFingerprint(Persona p){return sha256(String.join("|",p.getId().toString(),n(p.getName()),n(p.getDescription()),p.getDefaultLanguage().name(),p.getDefaultTone().name(),n(p.getAudience()),n(p.getVoiceDescription()),n(p.getStyleGuidelines()),n(p.getAvoidGuidelines()),n(p.getHashtagGuidelines()),n(p.getExampleCopy())));}
    private static String n(String v){return v==null?"":v;} private static String nonBlank(String v,String fallback){return v==null||v.isBlank()?fallback:v;}
    private static String bounded(String s,int max){return s.length()<=max?s:s.substring(0,max);}
    private Summary summary(OptimizationProposal p){return new Summary(p.getId(),p.getSourceReview().getId(),p.getRevision(),p.isCurrent(),p.getEngineVersion(),p.getFactor(),p.getStatus(),p.getBaselinePersonaId(),p.getBaselinePersonaNameSnapshot(),p.getCandidatePersonaId(),p.getCandidatePersonaNameSnapshot(),p.getMetric(),p.getStatistic(),p.getObservationWindow(),p.getProvider(),p.getCohortFrom(),p.getCohortTo(),p.getBaselineSample(),p.getCandidateSample(),p.getBaselineEligible(),p.getCandidateEligible(),p.getBaselineCoverage(),p.getCandidateCoverage(),p.getBaselineValue(),p.getCandidateValue(),p.getAbsoluteDifference(),p.getRelativeDifferencePercent(),p.getDirection(),p.getEvidenceFingerprint(),p.getRationale(),p.getLimitation(),p.getMaterializedExperimentId(),p.getCreatedAt(),p.getReviewedAt(),p.getMaterializedAt(),p.getOrigin(),p.getAutomationEngineVersion(),p.getAutomationRobotId(),p.getAutomationPolicyRevision(),p.getAutomationTrigger(),p.getAutomationOpportunityFingerprint());}

    private Evaluated evaluate(Workspace workspace,UUID reviewId,UUID candidatePersonaId){
        CampaignPerformanceReview review=requireReview(workspace,reviewId);
        if(review.getEvidenceStatus()!=EvidenceStatus.READY)throw conflict("SOURCE_REVIEW_NOT_READY");
        ReviewContext context=context(workspace,review);if(context==null)throw conflict("BASELINE_PERSONA_REQUIRED");
        if(context.personaId().equals(candidatePersonaId))throw bad("SAME_PERSONA");
        Persona baseline=requireActivePersona(workspace,context.personaId(),"BASELINE_PERSONA_INACTIVE");
        Persona candidate=requireActivePersona(workspace,candidatePersonaId,"CANDIDATE_PERSONA_INACTIVE");
        Instant cutoff=review.getEvidenceCutoffAt(),from=cutoff.minus(COHORT_LOOKBACK);
        Map<UUID,PersonaCohort> rows=store.personaCohorts(workspace.getId(),Set.of(baseline.getId(),candidate.getId()),
                context.provider(),review.getObservationWindow(),review.getPrimaryMetric(),from,cutoff);
        PersonaCohort a=rows.get(baseline.getId()),b=rows.get(candidate.getId());requireCohort(a,"BASELINE");requireCohort(b,"CANDIDATE");
        requireEvidence(a,"BASELINE");requireEvidence(b,"CANDIDATE");
        BigDecimal signed=b.median().subtract(a.median()),absolute=signed.abs();
        BigDecimal relative=a.median().signum()==0?null:absolute.divide(a.median().abs(),8,RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        if(relative==null||relative.compareTo(thresholds.getMaterialDifferencePercent())<0)throw conflict("NO_MATERIAL_OBSERVED_DIFFERENCE");
        Direction direction=signed.signum()>0?Direction.HIGHER_OBSERVED:signed.signum()<0?Direction.LOWER_OBSERVED:Direction.SIMILAR_OBSERVED;
        String af=personaFingerprint(baseline),bf=personaFingerprint(candidate);
        String evidence=fingerprint(review,context,baseline,candidate,a,b,relative,af,bf);
        String semantic=sha256(String.join("|",ENGINE_VERSION,"PERSONA",baseline.getId().toString(),af,candidate.getId().toString(),bf,
                review.getPrimaryMetric().name(),Statistic.MEDIAN.name(),review.getObservationWindow().name(),context.provider(),
                Integer.toString(a.sampleCount()),a.coverage().toPlainString(),a.median().toPlainString(),
                Integer.toString(b.sampleCount()),b.coverage().toPlainString(),b.median().toPlainString(),relative.toPlainString()));
        return new Evaluated(review,context,baseline,candidate,a,b,from,cutoff,absolute,relative,direction,af,bf,evidence,semantic);
    }

    private Summary persist(Evaluated e,AppUser actor,Origin origin,String automationEngine,UUID robotId,
            Integer policyRevision,String trigger){
        String opportunity=sha256(String.join("|","AUTONOMOUS_OPPORTUNITY_V1",robotId.toString(),e.semanticFingerprint()));
        store.lockOpportunity(opportunity);
        Optional<OptimizationProposal> duplicate=proposals.findByAutomationOpportunityFingerprint(opportunity);
        if(duplicate.isPresent())return summary(duplicate.get());
        var current=proposals.findBySourceReviewAndBaselinePersonaIdAndCandidatePersonaIdAndMetricAndStatisticAndCurrentTrue(
                e.review(),e.baseline().getId(),e.candidate().getId(),e.review().getPrimaryMetric(),Statistic.MEDIAN);
        current.ifPresent(OptimizationProposal::supersede);current.ifPresent(proposals::saveAndFlush);
        int revision=proposals.maxRevision(e.review(),e.baseline().getId(),e.candidate().getId(),e.review().getPrimaryMetric(),Statistic.MEDIAN)+1;
        Instant now=Instant.now(clock);String baselineName=nonBlank(e.context().personaName(),e.baseline().getName());
        OptimizationProposal proposal=new OptimizationProposal(e.baseline().getWorkspace(),e.review(),revision,e.baseline().getId(),
                baselineName,e.baselineFingerprint(),e.candidate().getId(),e.candidate().getName(),e.candidateFingerprint(),
                e.review().getPrimaryMetric(),e.review().getObservationWindow(),e.context().provider(),e.from(),e.cutoff(),
                e.a().sampleCount(),e.b().sampleCount(),e.a().eligibleCount(),e.b().eligibleCount(),e.a().coverage(),e.b().coverage(),
                e.a().median(),e.b().median(),e.absolute(),e.relative(),e.direction(),e.evidenceFingerprint(),
                rationale(baselineName,e.candidate().getName(),e.review(),e.direction()),String.join(" ",limitations(e.context().provider())),
                actor,now,origin,automationEngine,origin==Origin.AUTO_PROPOSE?robotId:null,
                origin==Origin.AUTO_PROPOSE?policyRevision:null,origin==Origin.AUTO_PROPOSE?trigger:null,opportunity);
        return summary(proposals.saveAndFlush(proposal));
    }

    private record Evaluated(CampaignPerformanceReview review,ReviewContext context,Persona baseline,Persona candidate,
            PersonaCohort a,PersonaCohort b,Instant from,Instant cutoff,BigDecimal absolute,BigDecimal relative,
            Direction direction,String baselineFingerprint,String candidateFingerprint,String evidenceFingerprint,
            String semanticFingerprint){}
    private static ResponseStatusException bad(String code){return new ResponseStatusException(HttpStatus.BAD_REQUEST,code);} private static ResponseStatusException conflict(String code){return new ResponseStatusException(HttpStatus.CONFLICT,code);}
}
