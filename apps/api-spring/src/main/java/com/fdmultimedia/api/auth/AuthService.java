package com.fdmultimedia.api.auth;

import com.fdmultimedia.api.auth.dto.AuthSessionResponse;
import com.fdmultimedia.api.auth.dto.UserSummary;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.users.AppUserRepository;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceMembershipRepository;
import com.fdmultimedia.api.workspaces.dto.WorkspaceSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final WorkspaceMembershipRepository memberships;

    public AuthService(AppUserRepository users, WorkspaceMembershipRepository memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public AuthSessionResponse sessionFor(AuthenticatedUser principal) {
        if (principal == null) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }

        AppUser user = users.findById(principal.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        List<WorkspaceMembership> userMemberships = memberships.findByUserOrderByCreatedAtAsc(user);
        WorkspaceMembership currentMembership = userMemberships.stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No workspace membership"));

        return new AuthSessionResponse(
                new UserSummary(user.getId(), user.getEmail(), user.getDisplayName()),
                toWorkspaceSummary(currentMembership));
    }

    @Transactional(readOnly = true)
    public AppUser requireUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public WorkspaceSummary toWorkspaceSummary(WorkspaceMembership membership) {
        return new WorkspaceSummary(
                membership.getWorkspace().getId(),
                membership.getWorkspace().getName(),
                membership.getWorkspace().getSlug(),
                membership.getRole());
    }
}
