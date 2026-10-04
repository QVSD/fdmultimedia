package com.fdmultimedia.api.optimization;

import com.fdmultimedia.api.analytics.CampaignPerformanceReviewCreatedEvent;
import com.fdmultimedia.api.robotchanges.*;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.ProposalAutomationMode;
import java.util.*;
import org.slf4j.*;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
public class AutonomousProposalReconciler {
    public static final int BATCH_LIMIT=100;
    private static final Logger log=LoggerFactory.getLogger(AutonomousProposalReconciler.class);
    private final RobotAdaptivePolicyRepository policies;private final AutonomousProposalService service;
    public AutonomousProposalReconciler(RobotAdaptivePolicyRepository policies,AutonomousProposalService service){this.policies=policies;this.service=service;}

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void afterReview(CampaignPerformanceReviewCreatedEvent event){evaluate(event.robotId(),"REVIEW_CREATED");}

    @Scheduled(fixedDelayString="${app.autonomous-proposals.reconcile-interval-ms:3600000}",
            initialDelayString="${app.autonomous-proposals.reconcile-initial-delay-ms:60000}")
    public void reconcile(){List<RobotAdaptivePolicy> rows=policies.findByEnabledTrueAndProposalAutomationModeOrderByUpdatedAtAsc(
                ProposalAutomationMode.AUTO_PROPOSE,PageRequest.of(0,BATCH_LIMIT));
        for(RobotAdaptivePolicy policy:rows)evaluate(policy.getRobotId(),"RECONCILIATION");}

    private void evaluate(java.util.UUID robotId,String trigger){try{var result=service.evaluateAndCreate(robotId,trigger);
        log.info("Autonomous proposal evaluation robotId={} eligible={} reasons={} proposalId={}",robotId,result.eligible(),result.reasons(),result.existingProposalId());}
        catch(RuntimeException ex){log.warn("Autonomous proposal evaluation failed robotId={} type={} code={} site={}",robotId,
                ex.getClass().getSimpleName(),failureCode(ex),failureSite(ex));}}

    static String failureCode(Throwable failure){
        Throwable deepest=failure;
        for(Throwable current=failure;current!=null;current=current.getCause()){
            deepest=current;
            if(current instanceof ConstraintViolationException violation&&violation.getConstraintName()!=null)
                return violation.getConstraintName();
        }
        return deepest.getClass().getSimpleName();
    }

    static String failureSite(Throwable failure){
        Throwable deepest=failure;
        while(deepest.getCause()!=null)deepest=deepest.getCause();
        return Arrays.stream(deepest.getStackTrace())
                .filter(frame->frame.getClassName().startsWith("com.fdmultimedia."))
                .findFirst().map(frame->frame.getClassName()+"#"+frame.getMethodName()+":"+frame.getLineNumber())
                .orElse("external");
    }
}
