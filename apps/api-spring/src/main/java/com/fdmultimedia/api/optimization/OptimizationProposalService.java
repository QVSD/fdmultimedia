package com.fdmultimedia.api.optimization;

import com.fdmultimedia.api.analytics.*;
import com.fdmultimedia.api.analytics.CampaignPerformanceModels.EvidenceStatus;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.experiments.*;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.*;
import com.fdmultimedia.api.optimization.OptimizationProposalStore.*;
import com.fdmultimedia.api.personas.*;
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
        CampaignPerformanceReview review=requireReview(workspace,request.sourceReviewId());
        if(review.getEvidenceStatus()!=EvidenceStatus.READY)throw conflict("SOURCE_REVIEW_NOT_READY");
        ReviewContext context=context(workspace,review);
        if(context==null)throw conflict("BASELINE_PERSONA_REQUIRED");
        if(context.personaId().equals(request.candidatePersonaId()))throw bad("SAME_PERSONA");
        Persona baseline=requireActivePersona(workspace,context.personaId(),"BASELINE_PERSONA_INACTIVE");
        Persona candidate=requireActivePersona(workspace,request.candidatePersonaId(),"CANDIDATE_PERSONA_INACTIVE");

        Instant cutoff=review.getEvidenceCutoffAt();Instant from=cutoff.minus(COHORT_LOOKBACK);
        Map<UUID,PersonaCohort> rows=store.personaCohorts(workspace.getId(),Set.of(baseline.getId(),candidate.getId()),
                context.provider(),review.getObservationWindow(),review.getPrimaryMetric(),from,cutoff);
        PersonaCohort a=rows.get(baseline.getId()),b=rows.get(candidate.getId());
        requireCohort(a,"BASELINE");requireCohort(b,"CANDIDATE");
        requireEvidence(a,"BASELINE");requireEvidence(b,"CANDIDATE");

        BigDecimal signed=b.median().subtract(a.median());BigDecimal absolute=signed.abs();
        BigDecimal relative=a.median().signum()==0?null:absolute.divide(a.median().abs(),8,RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        if(relative==null||relative.compareTo(thresholds.getMaterialDifferencePercent())<0)
            throw conflict("NO_MATERIAL_OBSERVED_DIFFERENCE");
        Direction direction=signed.signum()>0?Direction.HIGHER_OBSERVED:signed.signum()<0?Direction.LOWER_OBSERVED:Direction.SIMILAR_OBSERVED;
        String baselineFingerprint=personaFingerprint(baseline),candidateFingerprint=personaFingerprint(candidate);
        String fingerprint=fingerprint(review,context,baseline,candidate,a,b,relative,baselineFingerprint,candidateFingerprint);
        var current=proposals.findBySourceReviewAndBaselinePersonaIdAndCandidatePersonaIdAndMetricAndStatisticAndCurrentTrue(
                review,baseline.getId(),candidate.getId(),review.getPrimaryMetric(),Statistic.MEDIAN);
        current.ifPresent(OptimizationProposal::supersede);
        current.ifPresent(proposals::saveAndFlush);
        int revision=proposals.maxRevision(review,baseline.getId(),candidate.getId(),review.getPrimaryMetric(),Statistic.MEDIAN)+1;
        Instant now=Instant.now(clock);
        String baselineNameSnapshot=nonBlank(context.personaName(),baseline.getName());
        OptimizationProposal proposal=new OptimizationProposal(workspace,review,revision,baseline.getId(),
                baselineNameSnapshot,baselineFingerprint,candidate.getId(),candidate.getName(),candidateFingerprint,
                review.getPrimaryMetric(),review.getObservationWindow(),context.provider(),from,cutoff,a.sampleCount(),b.sampleCount(),
                a.eligibleCount(),b.eligibleCount(),a.coverage(),b.coverage(),a.median(),b.median(),absolute,relative,direction,
                fingerprint,rationale(baselineNameSnapshot,candidate.getName(),review,direction),String.join(" ",limitations(context.provider())),
                membership.getUser(),now);
        return summary(proposals.saveAndFlush(proposal));
    }

    @Transactional(readOnly=true)
    public List<Summary> list(AuthenticatedUser principal,int limit){
        Workspace workspace=auth.currentMembershipFor(principal).getWorkspace();
        int bounded=Math.min(Math.max(limit,1),MAX_LIST);
        return proposals.findByWorkspaceOrderByCreatedAtDesc(workspace,PageRequest.of(0,bounded)).stream().map(this::summary).toList();
    }

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
    private Summary summary(OptimizationProposal p){return new Summary(p.getId(),p.getSourceReview().getId(),p.getRevision(),p.isCurrent(),p.getEngineVersion(),p.getFactor(),p.getStatus(),p.getBaselinePersonaId(),p.getBaselinePersonaNameSnapshot(),p.getCandidatePersonaId(),p.getCandidatePersonaNameSnapshot(),p.getMetric(),p.getStatistic(),p.getObservationWindow(),p.getProvider(),p.getCohortFrom(),p.getCohortTo(),p.getBaselineSample(),p.getCandidateSample(),p.getBaselineEligible(),p.getCandidateEligible(),p.getBaselineCoverage(),p.getCandidateCoverage(),p.getBaselineValue(),p.getCandidateValue(),p.getAbsoluteDifference(),p.getRelativeDifferencePercent(),p.getDirection(),p.getEvidenceFingerprint(),p.getRationale(),p.getLimitation(),p.getMaterializedExperimentId(),p.getCreatedAt(),p.getReviewedAt(),p.getMaterializedAt());}
    private static ResponseStatusException bad(String code){return new ResponseStatusException(HttpStatus.BAD_REQUEST,code);} private static ResponseStatusException conflict(String code){return new ResponseStatusException(HttpStatus.CONFLICT,code);}
}
