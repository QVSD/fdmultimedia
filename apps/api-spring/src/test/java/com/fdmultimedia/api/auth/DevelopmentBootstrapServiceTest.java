package com.fdmultimedia.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.users.AppUserRepository;
import com.fdmultimedia.api.users.EmailNormalizer;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceMembershipRepository;
import com.fdmultimedia.api.workspaces.WorkspaceRepository;
import com.fdmultimedia.api.workers.WorkerCredential;
import com.fdmultimedia.api.workers.WorkerCredentialRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class DevelopmentBootstrapServiceTest {

    private final BootstrapProperties properties = new BootstrapProperties();
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    private final WorkerCredentialRepository workerCredentials = mock(WorkerCredentialRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final DevelopmentBootstrapService service = new DevelopmentBootstrapService(
            properties,
            users,
            workspaces,
            memberships,
            workerCredentials,
            passwordEncoder,
            new EmailNormalizer());

    @Test
    void bootstrapCreatesBcryptPasswordHash() {
        configureOwnerBootstrap();
        Workspace workspace = new Workspace("FD Multimedia", "fd-multimedia");
        when(workspaces.findBySlug("fd-multimedia")).thenReturn(Optional.of(workspace));
        when(users.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.empty());
        when(users.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(memberships.existsByUserAndWorkspace(any(), any())).thenReturn(false);
        when(memberships.save(any(WorkspaceMembership.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.bootstrap();

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("secret-password");
        assertThat(passwordEncoder.matches("secret-password", captor.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void bootstrapIsIdempotentForExistingWorkspaceUserAndMembership() {
        configureOwnerBootstrap();
        Workspace workspace = new Workspace("FD Multimedia", "fd-multimedia");
        AppUser owner = new AppUser("owner@example.com", "$2a$10$existing", "Owner");
        when(workspaces.findBySlug("fd-multimedia")).thenReturn(Optional.of(workspace));
        when(users.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(owner));
        when(memberships.existsByUserAndWorkspace(owner, workspace)).thenReturn(true);

        service.bootstrap();

        verify(workspaces, never()).save(any());
        verify(users, never()).save(any());
        verify(memberships, never()).save(any());
    }

    @Test
    void bootstrapCreatesHashedWorkerCredentialWhenConfigured() {
        configureOwnerBootstrap();
        UUID credentialId = UUID.randomUUID();
        properties.getWorkerCredential().setId(credentialId.toString());
        properties.getWorkerCredential().setName("local-dev-worker");
        properties.getWorkerCredential().setSecret("worker-secret");
        Workspace workspace = new Workspace("FD Multimedia", "fd-multimedia");
        when(workspaces.findBySlug("fd-multimedia")).thenReturn(Optional.of(workspace));
        when(users.findByEmailIgnoreCase("owner@example.com"))
                .thenReturn(Optional.of(new AppUser("owner@example.com", "$2a$10$existing", "Owner")));
        when(memberships.existsByUserAndWorkspace(any(), any())).thenReturn(true);
        when(workerCredentials.existsById(credentialId)).thenReturn(false);
        when(workerCredentials.save(any(WorkerCredential.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.bootstrap();

        ArgumentCaptor<WorkerCredential> captor = ArgumentCaptor.forClass(WorkerCredential.class);
        verify(workerCredentials).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(credentialId);
        assertThat(captor.getValue().getSecretHash()).isNotEqualTo("worker-secret");
        assertThat(passwordEncoder.matches("worker-secret", captor.getValue().getSecretHash())).isTrue();
    }

    private void configureOwnerBootstrap() {
        properties.getAdmin().setEmail(" Owner@Example.com ");
        properties.getAdmin().setPassword("secret-password");
        properties.getAdmin().setDisplayName("Owner");
        properties.getWorkspace().setName("FD Multimedia");
        properties.getWorkspace().setSlug("fd-multimedia");
    }
}
