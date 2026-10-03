package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.*;
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
public class RobotAdaptivePolicyService {
    public static final int DEFAULT_MAX_CHANGES=2,DEFAULT_WINDOW_DAYS=30,DEFAULT_COOLDOWN_HOURS=72;
    private final AuthService auth; private final RobotRepository robots; private final RobotAdaptivePolicyRepository policies;
    private final RobotAdaptivePolicyRevisionRepository history; private final Clock clock;
    public RobotAdaptivePolicyService(AuthService auth,RobotRepository robots,RobotAdaptivePolicyRepository policies,
            RobotAdaptivePolicyRevisionRepository history,Clock clock){this.auth=auth;this.robots=robots;this.policies=policies;this.history=history;this.clock=clock;}

    @Transactional(readOnly=true)
    public PolicySummary get(AuthenticatedUser user,UUID robotId){Workspace w=workspace(user);requireRobot(w,robotId);
        return policies.findByWorkspaceAndRobotId(w,robotId).map(this::summary).orElse(defaultSummary(robotId));}

    @Transactional
    public PolicySummary update(AuthenticatedUser user,UUID robotId,UpdateRequest request){var m=auth.currentMembershipFor(user);Workspace w=m.getWorkspace();
        robots.findByWorkspaceAndIdForUpdate(w,robotId).orElseThrow(()->notFound());
        RobotAdaptivePolicy policy=policies.findForUpdate(w,robotId).orElse(null);int current=policy==null?0:policy.getRevision();
        int expected=request==null||request.expectedRevision()==null?current:request.expectedRevision();
        if(expected!=current)throw new ResponseStatusException(HttpStatus.CONFLICT,"ADAPTIVE_POLICY_REVISION_CONFLICT");
        boolean enabled=request.enabled()==null?(policy==null||policy.isEnabled()):request.enabled();
        int max=value(request.maxAppliedChangesPerWindow(),policy==null?DEFAULT_MAX_CHANGES:policy.getMaxAppliedChangesPerWindow(),1,10,"maxAppliedChangesPerWindow");
        int days=value(request.changeBudgetWindowDays(),policy==null?DEFAULT_WINDOW_DAYS:policy.getChangeBudgetWindowDays(),1,365,"changeBudgetWindowDays");
        int cooldown=value(request.cooldownHours(),policy==null?DEFAULT_COOLDOWN_HOURS:policy.getCooldownHours(),0,2160,"cooldownHours");
        boolean noExperiment=bool(request.requireNoActiveExperiment(),policy==null||policy.isRequireNoActiveExperiment());
        boolean noPending=bool(request.requireNoPendingChange(),policy==null||policy.isRequireNoPendingChange());
        boolean observation=bool(request.requirePostChangeObservation(),policy==null||policy.isRequirePostChangeObservation());
        Instant now=Instant.now(clock);String old=policy==null?values(true,DEFAULT_MAX_CHANGES,DEFAULT_WINDOW_DAYS,DEFAULT_COOLDOWN_HOURS,true,true,true):values(policy);
        if(policy==null)policy=new RobotAdaptivePolicy(w,robotId,enabled,max,days,cooldown,noExperiment,noPending,observation,m.getUser(),now);
        else
            policy.update(enabled,max,days,cooldown,noExperiment,noPending,observation,m.getUser(),now);
        policies.saveAndFlush(policy);history.saveAndFlush(new RobotAdaptivePolicyRevision(w,robotId,policy.getRevision(),old,values(policy),m.getUser(),now));
        return summary(policy);
    }

    @Transactional(readOnly=true)
    public List<PolicyRevisionSummary> history(AuthenticatedUser user,UUID robotId,int limit){Workspace w=workspace(user);requireRobot(w,robotId);
        return history.findByWorkspaceAndRobotIdOrderByRevisionDesc(w,robotId,PageRequest.of(0,Math.min(Math.max(limit,1),100)))
                .stream().map(r->new PolicyRevisionSummary(r.getId(),r.getRobotId(),r.getRevision(),r.getPreviousValues(),r.getNewValues(),r.getActor().getId(),r.getCreatedAt())).toList();}

    PolicySummary effective(Workspace w,UUID robotId){return policies.findByWorkspaceAndRobotId(w,robotId).map(this::summary).orElse(defaultSummary(robotId));}
    private PolicySummary defaultSummary(UUID id){return new PolicySummary(id,0,false,true,DEFAULT_MAX_CHANGES,DEFAULT_WINDOW_DAYS,
            DEFAULT_COOLDOWN_HOURS,true,true,true,null);}
    private PolicySummary summary(RobotAdaptivePolicy p){return new PolicySummary(p.getRobotId(),p.getRevision(),true,p.isEnabled(),p.getMaxAppliedChangesPerWindow(),
            p.getChangeBudgetWindowDays(),p.getCooldownHours(),p.isRequireNoActiveExperiment(),p.isRequireNoPendingChange(),p.isRequirePostChangeObservation(),p.getUpdatedAt());}
    private static int value(Integer v,int d,int min,int max,String name){int x=v==null?d:v;if(x<min||x>max)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,name+" must be between "+min+" and "+max);return x;}
    private static boolean bool(Boolean v,boolean d){return v==null?d:v;}
    private static String values(RobotAdaptivePolicy p){return values(p.isEnabled(),p.getMaxAppliedChangesPerWindow(),p.getChangeBudgetWindowDays(),p.getCooldownHours(),p.isRequireNoActiveExperiment(),p.isRequireNoPendingChange(),p.isRequirePostChangeObservation());}
    private static String values(boolean e,int m,int d,int c,boolean x,boolean p,boolean o){return "enabled="+e+",max="+m+",windowDays="+d+",cooldownHours="+c+",noActiveExperiment="+x+",noPending="+p+",postChangeObservation="+o;}
    private Workspace workspace(AuthenticatedUser u){return auth.currentMembershipFor(u).getWorkspace();}
    private void requireRobot(Workspace w,UUID id){robots.findByWorkspaceAndId(w,id).orElseThrow(()->notFound());}
    private ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Robot not found");}
}
