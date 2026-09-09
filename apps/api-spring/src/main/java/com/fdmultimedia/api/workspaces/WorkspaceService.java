package com.fdmultimedia.api.workspaces;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.dto.WorkspaceSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceService {

    private final AuthService authService;
    private final WorkspaceMembershipRepository memberships;

    public WorkspaceService(AuthService authService, WorkspaceMembershipRepository memberships) {
        this.authService = authService;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceSummary> listFor(AuthenticatedUser principal) {
        AppUser user = authService.requireUser(principal.id());
        return memberships.findByUserOrderByCreatedAtAsc(user).stream()
                .map(authService::toWorkspaceSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceSummary getFor(AuthenticatedUser principal, UUID workspaceId) {
        return memberships.findByUserIdAndWorkspaceId(principal.id(), workspaceId)
                .map(authService::toWorkspaceSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace access denied"));
    }
}
