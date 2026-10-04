package com.fdmultimedia.api.optimization;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/optimization-proposals")
public class OptimizationProposalController {
    private final OptimizationProposalService service;
    public OptimizationProposalController(OptimizationProposalService service){this.service=service;}
    @GetMapping public List<Summary> list(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam(defaultValue="50") int limit,
            @RequestParam(required=false) Origin origin){return service.list(user,limit,origin);}
    @GetMapping("/{id}") public Summary get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID id){return service.get(user,id);}
    @GetMapping("/eligibility/{reviewId}") public Eligibility eligibility(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID reviewId){return service.eligibility(user,reviewId);}
    @PostMapping public Summary create(@AuthenticationPrincipal AuthenticatedUser user,@RequestBody CreateRequest request){return service.create(user,request);}
    @PostMapping("/{id}/approve") public Summary approve(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID id){return service.approve(user,id);}
    @PostMapping("/{id}/reject") public Summary reject(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID id){return service.reject(user,id);}
    @PostMapping("/{id}/materialize-experiment") public Summary materialize(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID id){return service.materialize(user,id);}
}
