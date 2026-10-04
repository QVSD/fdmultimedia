package com.fdmultimedia.api.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fdmultimedia.api.analytics.CampaignPerformanceReviewCreatedEvent;
import com.fdmultimedia.api.optimization.AutonomousProposalModels.Evaluation;
import com.fdmultimedia.api.robotchanges.*;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.ProposalAutomationMode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class AutonomousProposalReconcilerTest {
    private final RobotAdaptivePolicyRepository policies=mock(RobotAdaptivePolicyRepository.class);
    private final AutonomousProposalService service=mock(AutonomousProposalService.class);
    private final AutonomousProposalReconciler reconciler=new AutonomousProposalReconciler(policies,service);

    @Test void reviewEventUsesBoundedAutonomousOrchestration(){
        UUID robotId=UUID.randomUUID();when(service.evaluateAndCreate(robotId,"REVIEW_CREATED"))
                .thenReturn(new Evaluation(robotId,false,List.of(),0,null,0,null,null,null,null));
        reconciler.afterReview(new CampaignPerformanceReviewCreatedEvent(UUID.randomUUID(),robotId));
        verify(service).evaluateAndCreate(robotId,"REVIEW_CREATED");
    }

    @Test void scheduledReconciliationRequestsAtMostOneHundredOptedInRobots(){
        when(policies.findByEnabledTrueAndProposalAutomationModeOrderByUpdatedAtAsc(eq(ProposalAutomationMode.AUTO_PROPOSE),any(Pageable.class)))
                .thenReturn(List.of());
        reconciler.reconcile();
        verify(policies).findByEnabledTrueAndProposalAutomationModeOrderByUpdatedAtAsc(eq(ProposalAutomationMode.AUTO_PROPOSE),
                argThat(p->p.getPageSize()==AutonomousProposalReconciler.BATCH_LIMIT));
        verifyNoInteractions(service);
    }

    @Test void persistenceFailureLoggingExposesOnlyTheBoundedConstraintCode(){
        var violation=new org.hibernate.exception.ConstraintViolationException("details",null,"bounded_constraint");
        assertThat(AutonomousProposalReconciler.failureCode(new RuntimeException("wrapper",violation)))
                .isEqualTo("bounded_constraint");
        assertThat(AutonomousProposalReconciler.failureCode(new RuntimeException("provider body")))
                .isEqualTo("RuntimeException");
        assertThat(AutonomousProposalReconciler.failureSite(new RuntimeException("provider body"))).isNotBlank();
    }
}
