package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.analytics.PerformanceInsightProperties;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.experiments.*;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.*;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.Status;
import com.fdmultimedia.api.robots.*;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdaptiveGuardrailService {
    public static final String ENGINE_VERSION="ADAPTIVE_GUARDRAILS_V1";
    public static final String OBSERVATION_WINDOW="H72";
    private final AuthService auth; private final RobotChangeProposalRepository proposals;
    private final RobotConfigurationRevisionRepository revisions; private final RobotAdaptivePolicyService policies;
    private final AdaptiveGuardrailEvaluationRepository evaluations; private final ExperimentRepository experiments;
    private final RobotRepository robots; private final AdaptiveGuardrailStore store; private final PerformanceInsightProperties thresholds; private final Clock clock;
    public AdaptiveGuardrailService(AuthService auth,RobotChangeProposalRepository proposals,
            RobotConfigurationRevisionRepository revisions,RobotAdaptivePolicyService policies,
            AdaptiveGuardrailEvaluationRepository evaluations,ExperimentRepository experiments,RobotRepository robots,
            AdaptiveGuardrailStore store,PerformanceInsightProperties thresholds,Clock clock){this.auth=auth;this.proposals=proposals;
        this.revisions=revisions;this.policies=policies;this.evaluations=evaluations;this.experiments=experiments;
        this.robots=robots;this.store=store;this.thresholds=thresholds;this.clock=clock;}

    @Transactional
    public EvaluationSummary check(AuthenticatedUser user,UUID proposalId){Workspace w=auth.currentMembershipFor(user).getWorkspace();
        RobotChangeProposal p=proposals.findByWorkspaceAndId(w,proposalId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Robot change proposal not found"));
        Robot robot=robots.findByWorkspaceAndId(w,p.getTargetRobotId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Robot not found"));
        return summary(evaluateAndPersist(w,robot,p,Trigger.CHECK));}

    AdaptiveGuardrailEvaluation evaluateAndPersist(Workspace w,Robot robot,RobotChangeProposal p,Trigger trigger){
        PolicySummary policy=policies.effective(w,robot.getId());Instant now=Instant.now(clock);
        List<Reason> reasons=new ArrayList<>();if(!policy.enabled())reasons.add(Reason.POLICY_DISABLED);
        Instant budgetStart=now.minus(Duration.ofDays(policy.changeBudgetWindowDays()));
        int used=(int)revisions.countByRobotIdAndChangeTypeAndCreatedAtGreaterThanEqual(robot.getId(),RobotChangeProposalModels.ChangeType.PERSONA_CHANGE,budgetStart);
        if(used>=policy.maxAppliedChangesPerWindow())reasons.add(Reason.CHANGE_BUDGET_EXHAUSTED);
        RobotConfigurationRevision latest=revisions.findTopByRobotIdOrderByRevisionDesc(robot.getId()).orElse(null);
        Instant cooldownEnd=latest==null||policy.cooldownHours()==0?null:latest.getCreatedAt().plus(Duration.ofHours(policy.cooldownHours()));
        if(cooldownEnd!=null&&now.isBefore(cooldownEnd))reasons.add(Reason.COOLDOWN_ACTIVE);
        UUID activeExperiment=null;
        if(policy.requireNoActiveExperiment()&&robot.getExperimentId()!=null){Experiment e=experiments.findByWorkspaceAndId(w,robot.getExperimentId()).orElse(null);
            if(e!=null&&!e.isTerminal()){activeExperiment=e.getId();reasons.add(Reason.ACTIVE_EXPERIMENT);}}
        int pending=0;
        if(policy.requireNoPendingChange()){
            List<RobotChangeProposal> rows=proposals.findByWorkspaceAndTargetRobotIdOrderByCreatedAtAsc(w,robot.getId(),PageRequest.of(0,101));
            pending=(int)rows.stream().filter(x->!x.getId().equals(p.getId())).filter(x->x.getStatus()==Status.READY_FOR_REVIEW||x.getStatus()==Status.APPROVED)
                    .filter(x->older(x,p)).count();
            if(pending>0)reasons.add(Reason.PENDING_CHANGE_EXISTS);
        }
        Observation observation=Observation.empty();
        if(policy.requirePostChangeObservation()&&latest!=null){observation=store.observation(w.getId(),robot.getId(),latest.getCreatedAt(),now);
            boolean sufficient=observation.metricSamples()>=thresholds.getMinSampleSize()&&observation.coverage()!=null&&observation.coverage().compareTo(thresholds.getMinCoverage())>=0;
            if(!sufficient)reasons.add(Reason.POST_CHANGE_OBSERVATION_REQUIRED);
        }
        AdaptiveGuardrailEvaluation result=new AdaptiveGuardrailEvaluation(w,p.getId(),robot.getId(),trigger,policy.revision(),List.copyOf(reasons),
                policy.maxAppliedChangesPerWindow(),used,policy.changeBudgetWindowDays(),latest==null?null:latest.getId(),
                latest==null?null:latest.getCreatedAt(),policy.cooldownHours(),cooldownEnd,activeExperiment,pending,observation,now);
        return evaluations.saveAndFlush(result);
    }

    EvaluationSummary summary(AdaptiveGuardrailEvaluation e){List<Reason> reasons=e.getReasonCodes().isBlank()?List.of():Arrays.stream(e.getReasonCodes().split(",")).map(Reason::valueOf).toList();
        return new EvaluationSummary(e.getId(),e.getProposalId(),e.getRobotId(),e.getEngineVersion(),e.getTrigger(),e.getPolicyRevision(),e.isEligible(),reasons,
                e.getBudgetAllowed(),e.getBudgetUsed(),Math.max(0,e.getBudgetAllowed()-e.getBudgetUsed()),e.getBudgetWindowDays(),e.getLatestConfigurationRevisionId(),
                e.getLastConfigurationChangeAt(),e.getCooldownHours(),e.getCooldownEndsAt(),e.getActiveExperimentId(),e.getPendingProposalCount(),e.getRunsSinceRevision(),
                e.getPublicationsSinceRevision(),e.getEligibleByAgeCount(),e.getAnalyticsPublicationCount(),e.getMetricSampleCount(),e.getCoverage(),
                thresholds.getMinSampleSize(),thresholds.getMinCoverage(),OBSERVATION_WINDOW,e.getEvaluatedAt());}
    private static boolean older(RobotChangeProposal a,RobotChangeProposal b){int cmp=a.getCreatedAt().compareTo(b.getCreatedAt());return cmp<0||(cmp==0&&a.getId().compareTo(b.getId())<0);}
}
