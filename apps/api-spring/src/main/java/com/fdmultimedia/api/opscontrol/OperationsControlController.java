package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.opscontrol.OperationsControlService.*;
import com.fdmultimedia.api.opscontrol.OpsModels.*;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-only, read-mostly operations API. The single write is acknowledging an incident (CSRF-protected like every other mutation).
 * Responses are never cacheable.
 */
@RestController
@RequestMapping("/api/operations")
public class OperationsControlController {
    private final OperationsControlService service;
    private final OperatorAccess access;

    public OperationsControlController(OperationsControlService service, OperatorAccess access) {
        this.service = service; this.access = access;
    }

    private OperatorAccess.Operator operator(Authentication authentication) {
        return access.require((AuthenticatedUser) authentication.getPrincipal());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/overview")
    public ResponseEntity<Overview> overview(Authentication authentication) {
        return noStore(service.overview(operator(authentication).workspaceId()));
    }

    @GetMapping("/workers")
    public ResponseEntity<WorkersView> workers(Authentication authentication, @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return noStore(service.workers(operator(authentication).workspaceId(), state, page, size));
    }

    @GetMapping("/jobs")
    public ResponseEntity<JobsView> jobs(Authentication authentication, @RequestParam(required = false) String status,
            @RequestParam(required = false) String type, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return noStore(service.jobs(operator(authentication).workspaceId(), status, type, page, size));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<JobDetail> job(Authentication authentication, @PathVariable UUID id) {
        return noStore(service.job(operator(authentication).workspaceId(), id));
    }

    @GetMapping("/schedulers")
    public ResponseEntity<SchedulersView> schedulers(Authentication authentication) {
        return noStore(service.schedulers(operator(authentication).workspaceId()));
    }

    @GetMapping("/publishing")
    public ResponseEntity<PublishingView> publishing(Authentication authentication, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return noStore(service.publishing(operator(authentication).workspaceId(), page, size));
    }

    @GetMapping("/incidents")
    public ResponseEntity<IncidentsView> incidents(Authentication authentication, @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(required = false) String severity, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return noStore(service.incidents(operator(authentication).workspaceId(), status, severity, page, size));
    }

    @PostMapping("/incidents/{id}/acknowledge")
    public ResponseEntity<Incident> acknowledge(Authentication authentication, @PathVariable UUID id) {
        OperatorAccess.Operator operator = operator(authentication);
        return noStore(service.acknowledge(operator.workspaceId(), operator.userId(), id));
    }
}
