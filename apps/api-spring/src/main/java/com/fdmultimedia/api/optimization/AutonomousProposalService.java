package com.fdmultimedia.api.optimization;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.Decision;
import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryService;
import com.fdmultimedia.api.analytics.CampaignPerformanceReview;
import com.fdmultimedia.api.analytics.CampaignPerformanceReviewRepository;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.experiments.*;
import com.fdmultimedia.api.optimization.AutonomousProposalModels.*;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.AutonomousEvidence;
import com.fdmultimedia.api.personas.*;
import com.fdmultimedia.api.robotchanges.*;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.ProposalAutomationMode;
import com.fdmultimedia.api.robots.*;
import com.fdmultimedia.api.workspaces.Workspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AutonomousProposalService {
    public static final String ENGINE_VERSION="AUTONOMOUS_PROPOSALS_V1";
    public static final int CANDIDATE_LIMIT=20;
    private final AuthService auth;private final RobotRepository robots;private final RobotAdaptivePolicyRepository policies;
    private final CampaignPerformanceReviewRepository reviews;private final PersonaRepository personas;
    private final OptimizationProposalRepository proposals;private final OptimizationProposalService proposalService;
    private final RobotChangeProposalRepository robotChanges;private final ExperimentRepository experiments;
    private final RobotConfigurationRevisionRepository revisions;
    private final AdaptiveMemoryService memory;
    public AutonomousProposalService(AuthService auth,RobotRepository robots,RobotAdaptivePolicyRepository policies,
            CampaignPerformanceReviewRepository reviews,PersonaRepository personas,OptimizationProposalRepository proposals,
            OptimizationProposalService proposalService,RobotChangeProposalRepository robotChanges,
            ExperimentRepository experiments,RobotConfigurationRevisionRepository revisions,AdaptiveMemoryService memory){this.auth=auth;
        this.robots=robots;this.policies=policies;this.reviews=reviews;this.personas=personas;this.proposals=proposals;
        this.proposalService=proposalService;this.robotChanges=robotChanges;this.experiments=experiments;this.revisions=revisions;this.memory=memory;}

    @Transactional(readOnly=true)
    public Evaluation dryRun(AuthenticatedUser principal,UUID robotId){Workspace w=auth.currentMembershipFor(principal).getWorkspace();
        Robot robot=robots.findByWorkspaceAndId(w,robotId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Robot not found"));
        RobotAdaptivePolicy policy=policies.findByWorkspaceAndRobotId(w,robotId).orElse(null);return evaluate(w,robot,policy,false,false);}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Evaluation evaluateAndCreate(UUID robotId,String trigger){Robot robot=robots.findByIdForUpdate(robotId).orElse(null);
        if(robot==null)return new Evaluation(robotId,false,List.of(Reason.NO_ELIGIBLE_REVIEW),0,null,0,null,null,null,null);
        Workspace w=robot.getWorkspace();RobotAdaptivePolicy policy=policies.findForUpdate(w,robotId).orElse(null);
        Evaluation evaluation=evaluate(w,robot,policy,true,true);if(!evaluation.eligible())return evaluation;
        OptimizationProposalModels.Summary created=proposalService.createAutonomous(w,policy.getUpdatedBy(),evaluation.sourceReviewId(),
                evaluation.selectedCandidatePersonaId(),robotId,policy.getRevision(),trigger,ENGINE_VERSION);
        return new Evaluation(robotId,true,List.of(),policy.getRevision(),evaluation.sourceReviewId(),
                evaluation.candidateCountConsidered(),evaluation.selectedCandidatePersonaId(),created.evidenceFingerprint(),
                created.automationOpportunityFingerprint(),created.id(),evaluation.memorySkippedCandidates());}

    private Evaluation evaluate(Workspace w,Robot robot,RobotAdaptivePolicy policy,boolean lockExperiment,boolean reconcileMemory){List<Reason> reasons=new ArrayList<>();
        if(policy==null||policy.getProposalAutomationMode()!=ProposalAutomationMode.AUTO_PROPOSE)reasons.add(Reason.MANUAL_ONLY);
        if(policy!=null&&!policy.isEnabled())reasons.add(Reason.ADAPTIVE_POLICY_DISABLED);
        if(robot.getPersona()==null)reasons.add(Reason.NO_BASELINE_PERSONA);
        if(robot.getExperimentId()!=null){Experiment e=(lockExperiment?experiments.findByWorkspaceAndIdForUpdate(w,robot.getExperimentId()):
                experiments.findByWorkspaceAndId(w,robot.getExperimentId())).orElse(null);
            if(e!=null&&!e.isTerminal())reasons.add(Reason.ACTIVE_EXPERIMENT);}
        if(proposals.countUnresolvedForRobot(robot.getId())>0)reasons.add(Reason.PENDING_OPTIMIZATION_PROPOSAL);
        if(robotChanges.countPendingForRobot(w,robot.getId())>0)reasons.add(Reason.PENDING_ROBOT_CHANGE_PROPOSAL);
        List<CampaignPerformanceReview> canonical=reviews.findCanonicalReadyForRobot(w,robot.getId(),PageRequest.of(0,1));
        CampaignPerformanceReview review=canonical.isEmpty()?null:canonical.get(0);
        if(review==null)reasons.add(Reason.NO_ELIGIBLE_REVIEW);
        Optional<RobotConfigurationRevision> latest=revisions.findTopByRobotIdOrderByRevisionDesc(robot.getId());
        if(review!=null&&latest.isPresent()&&review.getRobotRun().getCreatedAt().isBefore(latest.get().getCreatedAt())){
            reasons.add(Reason.STALE_EVIDENCE);if(policy!=null&&policy.isRequirePostChangeObservation())reasons.add(Reason.POST_CHANGE_OBSERVATION_REQUIRED);}
        if(!reasons.isEmpty())return result(robot,policy,review,reasons,0,null,null,null,null);
        List<Persona> candidates=personas.findByWorkspaceAndStatusOrderByIdAsc(w,PersonaStatus.ACTIVE,PageRequest.of(0,CANDIDATE_LIMIT+1));
        int considered=0;AutonomousEvidence selected=null;List<MemorySkip> skipped=new ArrayList<>();
        AdaptiveMemoryService.Screen screen=reconcileMemory?memory.screen(robot.getId(),robot.getPersona().getId()):
                memory.screenReadOnly(robot.getId(),robot.getPersona().getId()); // one bounded read; lifecycle/dry-run never writes
        for(Persona candidate:candidates){if(candidate.getId().equals(robot.getPersona().getId()))continue;if(considered>=CANDIDATE_LIMIT)break;considered++;
            try{AutonomousEvidence evidence=proposalService.evaluateAutonomous(w,review.getId(),candidate.getId());
                if(evidence.baselinePersonaId().equals(robot.getPersona().getId())){
                    // Phase 17N: memory only FILTERS an otherwise-valid candidate; the canonical UUID order is never changed.
                    Decision decision=screen.decide(candidate.getId());
                    if(!decision.eligible()){skipped.add(new MemorySkip(candidate.getId(),decision.reasons().stream().map(Enum::name).toList(),
                            decision.suppressionUntil(),decision.latestOutcome()==null?null:decision.latestOutcome().name()));continue;}
                    selected=evidence;break;}}
            catch(ResponseStatusException ignored){/* canonical 17H gate rejected this stable candidate */}}
        if(selected==null)return result(robot,policy,review,List.of(skipped.isEmpty()?Reason.NO_ELIGIBLE_CANDIDATE:Reason.ALL_CANDIDATES_MEMORY_SUPPRESSED),
                considered,null,null,null,null,skipped);
        String opportunity=opportunity(robot.getId(),selected.semanticEvidenceFingerprint());
        Optional<OptimizationProposal> duplicate=proposals.findByAutomationOpportunityFingerprint(opportunity);
        if(duplicate.isPresent())return result(robot,policy,review,List.of(Reason.DUPLICATE_OPPORTUNITY),considered,
                selected.candidatePersonaId(),selected.evidenceFingerprint(),opportunity,duplicate.get().getId(),skipped);
        return result(robot,policy,review,List.of(),considered,selected.candidatePersonaId(),selected.evidenceFingerprint(),opportunity,null,skipped);
    }

    private Evaluation result(Robot robot,RobotAdaptivePolicy policy,CampaignPerformanceReview review,List<Reason> reasons,
            int considered,UUID candidate,String evidence,String opportunity,UUID existing){return result(robot,policy,review,reasons,
                considered,candidate,evidence,opportunity,existing,List.of());}
    private Evaluation result(Robot robot,RobotAdaptivePolicy policy,CampaignPerformanceReview review,List<Reason> reasons,
            int considered,UUID candidate,String evidence,String opportunity,UUID existing,List<MemorySkip> skipped){return new Evaluation(robot.getId(),
                reasons.isEmpty(),List.copyOf(reasons),policy==null?0:policy.getRevision(),review==null?null:review.getId(),
                considered,candidate,evidence,opportunity,existing,List.copyOf(skipped));}
    static String opportunity(UUID robotId,String semantic){return sha256("AUTONOMOUS_OPPORTUNITY_V1|"+robotId+"|"+semantic);}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
