package com.fdmultimedia.api.workspaces;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.dto.WorkspaceSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public List<WorkspaceSummary> list(Authentication authentication) {
        return workspaceService.listFor((AuthenticatedUser) authentication.getPrincipal());
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceSummary get(Authentication authentication, @PathVariable UUID workspaceId) {
        return workspaceService.getFor((AuthenticatedUser) authentication.getPrincipal(), workspaceId);
    }
}
