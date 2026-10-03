package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class RobotAdaptivePolicyController {
    private final RobotAdaptivePolicyService policies; private final AdaptiveGuardrailService guardrails;
    public RobotAdaptivePolicyController(RobotAdaptivePolicyService policies,AdaptiveGuardrailService guardrails){this.policies=policies;this.guardrails=guardrails;}
    @GetMapping("/api/robots/{robotId}/adaptive-policy")
    public PolicySummary policy(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID robotId){return policies.get(user,robotId);}
    @PutMapping("/api/robots/{robotId}/adaptive-policy")
    public PolicySummary update(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID robotId,@RequestBody UpdateRequest request){return policies.update(user,robotId,request);}
    @GetMapping("/api/robots/{robotId}/adaptive-policy/revisions")
    public List<PolicyRevisionSummary> history(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID robotId,@RequestParam(defaultValue="50") int limit){return policies.history(user,robotId,limit);}
    @GetMapping("/api/robot-change-proposals/{id}/guardrails")
    public EvaluationSummary guardrails(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID id){return guardrails.check(user,id);}
}
