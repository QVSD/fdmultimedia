package com.fdmultimedia.api.auth;

import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.users.AppUserRepository;
import com.fdmultimedia.api.users.EmailNormalizer;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceMembershipRepository;
import com.fdmultimedia.api.workspaces.WorkspaceRepository;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("dev")
public class DevelopmentBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentBootstrapService.class);

    private final BootstrapProperties properties;
    private final AppUserRepository users;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final EmailNormalizer emailNormalizer;

    public DevelopmentBootstrapService(
            BootstrapProperties properties,
            AppUserRepository users,
            WorkspaceRepository workspaces,
            WorkspaceMembershipRepository memberships,
            PasswordEncoder passwordEncoder,
            EmailNormalizer emailNormalizer) {
        this.properties = properties;
        this.users = users;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.emailNormalizer = emailNormalizer;
    }

    @Transactional
    public void bootstrap() {
        if (!hasRequiredAdminConfig()) {
            log.info("Development bootstrap skipped because admin/workspace configuration is incomplete");
            return;
        }

        Workspace workspace = workspaces.findBySlug(properties.getWorkspace().getSlug().trim())
                .orElseGet(() -> workspaces.save(new Workspace(
                        properties.getWorkspace().getName().trim(),
                        properties.getWorkspace().getSlug().trim())));

        AppUser owner = findOrCreateUser(
                properties.getAdmin().getEmail(),
                properties.getAdmin().getPassword(),
                properties.getAdmin().getDisplayName());
        ensureMembership(workspace, owner, WorkspaceRole.OWNER);

        if (hasRequiredSecondUserConfig()) {
            AppUser secondUser = findOrCreateUser(
                    properties.getSecondUser().getEmail(),
                    properties.getSecondUser().getPassword(),
                    properties.getSecondUser().getDisplayName());
            ensureMembership(workspace, secondUser, properties.getSecondUser().getRole());
        }

        log.info("Development bootstrap ensured workspace {} and configured memberships", workspace.getSlug());
    }

    private AppUser findOrCreateUser(String email, String password, String displayName) {
        String normalizedEmail = emailNormalizer.normalize(email);
        return users.findByEmailIgnoreCase(normalizedEmail)
                .orElseGet(() -> users.save(new AppUser(
                        normalizedEmail,
                        passwordEncoder.encode(password),
                        displayName.trim())));
    }

    private void ensureMembership(Workspace workspace, AppUser user, WorkspaceRole role) {
        if (!memberships.existsByUserAndWorkspace(user, workspace)) {
            memberships.save(new WorkspaceMembership(workspace, user, role));
        }
    }

    private boolean hasRequiredAdminConfig() {
        return hasText(properties.getAdmin().getEmail())
                && hasText(properties.getAdmin().getPassword())
                && hasText(properties.getAdmin().getDisplayName())
                && hasText(properties.getWorkspace().getName())
                && hasText(properties.getWorkspace().getSlug());
    }

    private boolean hasRequiredSecondUserConfig() {
        return hasText(properties.getSecondUser().getEmail())
                && hasText(properties.getSecondUser().getPassword())
                && hasText(properties.getSecondUser().getDisplayName());
    }

    private boolean hasText(String value) {
        return StringUtils.hasText(value);
    }
}
