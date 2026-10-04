package com.fdmultimedia.api.optimization;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.optimization.AutonomousProposalModels.Evaluation;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AutonomousProposalController {
    private final AutonomousProposalService service;
    public AutonomousProposalController(AutonomousProposalService service){this.service=service;}
    @GetMapping("/api/robots/{robotId}/adaptive-proposal-eligibility")
    public Evaluation eligibility(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID robotId){return service.dryRun(user,robotId);}
}
