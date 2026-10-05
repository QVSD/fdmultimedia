package com.fdmultimedia.api.opscontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OperatorAccessTest {
    private final AuthService auth = mock(AuthService.class);
    private final OperatorAccess access = new OperatorAccess(auth);
    private final AuthenticatedUser principal = mock(AuthenticatedUser.class);

    private WorkspaceMembership membership(WorkspaceRole role, UUID workspaceId) {
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(workspaceId);
        WorkspaceMembership membership = mock(WorkspaceMembership.class);
        when(membership.getRole()).thenReturn(role);
        when(membership.getWorkspace()).thenReturn(workspace);
        when(auth.currentMembershipFor(principal)).thenReturn(membership);
        return membership;
    }

    @Test
    void ownersAndAdminsAreOperators() {
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(principal.id()).thenReturn(userId);
        membership(WorkspaceRole.OWNER, workspaceId);
        assertThat(access.require(principal)).isEqualTo(new OperatorAccess.Operator(userId, workspaceId, WorkspaceRole.OWNER));
        membership(WorkspaceRole.ADMIN, workspaceId);
        assertThat(access.require(principal).role()).isEqualTo(WorkspaceRole.ADMIN);
    }

    @Test
    void membersAreForbidden() {
        membership(WorkspaceRole.MEMBER, UUID.randomUUID());
        assertThatThrownBy(() -> access.require(principal)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void operatorRoleSetIsExactlyOwnerAndAdmin() {
        assertThat(OperatorAccess.isOperator(WorkspaceRole.OWNER)).isTrue();
        assertThat(OperatorAccess.isOperator(WorkspaceRole.ADMIN)).isTrue();
        assertThat(OperatorAccess.isOperator(WorkspaceRole.MEMBER)).isFalse();
        assertThat(OperatorAccess.isOperator(null)).isFalse();
    }
}
