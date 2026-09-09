package com.fdmultimedia.api.workspaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.dto.WorkspaceSummary;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class WorkspaceServiceTest {

    private final AuthService authService = mock(AuthService.class);
    private final WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    private final WorkspaceService service = new WorkspaceService(authService, memberships);

    @Test
    void userCanAccessWorkspaceWhereTheyHaveMembership() {
        AppUser user = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        Workspace workspace = new Workspace("FD Multimedia", "fd-multimedia");
        WorkspaceMembership membership = new WorkspaceMembership(workspace, user, WorkspaceRole.OWNER);
        AuthenticatedUser principal = new AuthenticatedUser(user);
        WorkspaceSummary summary = new WorkspaceSummary(workspace.getId(), "FD Multimedia", "fd-multimedia", WorkspaceRole.OWNER);

        when(memberships.findByUserIdAndWorkspaceId(principal.id(), workspace.getId())).thenReturn(Optional.of(membership));
        when(authService.toWorkspaceSummary(membership)).thenReturn(summary);

        assertThat(service.getFor(principal, workspace.getId())).isEqualTo(summary);
    }

    @Test
    void userCannotAccessWorkspaceWithoutMembership() {
        AppUser user = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        AuthenticatedUser principal = new AuthenticatedUser(user);
        UUID otherWorkspaceId = UUID.randomUUID();

        when(memberships.findByUserIdAndWorkspaceId(principal.id(), otherWorkspaceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFor(principal, otherWorkspaceId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }
}
