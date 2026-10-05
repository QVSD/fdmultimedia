package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operations visibility is for operators only: the workspace OWNER and ADMIN roles. There is no separate role system; this reuses the
 * workspace role that already exists. A MEMBER receives 403, never a partial view.
 */
@Component
public class OperatorAccess {
    public record Operator(UUID userId, UUID workspaceId, WorkspaceRole role) {}

    private final AuthService auth;

    public OperatorAccess(AuthService auth) { this.auth = auth; }

    @Transactional(readOnly = true)
    public Operator require(AuthenticatedUser principal) {
        WorkspaceMembership membership = auth.currentMembershipFor(principal);
        if (!isOperator(membership.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operator access required");
        }
        Workspace workspace = membership.getWorkspace();
        return new Operator(principal.id(), workspace.getId(), membership.getRole());
    }

    public static boolean isOperator(WorkspaceRole role) {
        return role == WorkspaceRole.OWNER || role == WorkspaceRole.ADMIN;
    }
}
