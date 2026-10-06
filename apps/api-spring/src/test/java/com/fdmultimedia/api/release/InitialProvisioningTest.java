package com.fdmultimedia.api.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.users.AppUserRepository;
import com.fdmultimedia.api.users.EmailNormalizer;
import com.fdmultimedia.api.workers.WorkerCredential;
import com.fdmultimedia.api.workers.WorkerCredentialRepository;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceMembershipRepository;
import com.fdmultimedia.api.workspaces.WorkspaceRepository;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class InitialProvisioningTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    private final WorkerCredentialRepository credentials = mock(WorkerCredentialRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private static final String PASSWORD = "Vq7!rTz2Lm9XcB4n";
    private static final String SECRET = "wK8dP3zQ7mV2xN6rT9bL4cH1sJ5";

    private InitialProvisioning provisioning(String email, String password, String passwordFile, String slug, String workerId, String workerSecret) {
        return new InitialProvisioning(users, workspaces, memberships, credentials, encoder, new EmailNormalizer(), email, "Pilot Owner", password,
                passwordFile, "Pilot Workspace", slug, workerId, "pilot-worker", workerSecret, "");
    }

    @Test
    void emptyInstallationGetsAWorkspaceAndAnOwnerWithAHashedPassword() {
        when(users.count()).thenReturn(0L);
        when(workspaces.save(any())).thenAnswer(i -> i.getArgument(0));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        provisioning("Owner@Pilot.Example.org", PASSWORD, "", "pilot", "", "").run();
        ArgumentCaptor<AppUser> user = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(user.capture());
        assertThat(user.getValue().getEmail()).isEqualTo("owner@pilot.example.org");
        assertThat(user.getValue().getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, user.getValue().getPasswordHash())).isTrue();
        ArgumentCaptor<WorkspaceMembership> membership = ArgumentCaptor.forClass(WorkspaceMembership.class);
        verify(memberships).save(membership.capture());
        assertThat(membership.getValue().getRole()).isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    void anInstallationThatAlreadyHasUsersIsNeverTouched() {
        when(users.count()).thenReturn(2L);
        when(workspaces.findAll()).thenReturn(List.of(new Workspace("W", "w")));
        provisioning("someone@pilot.example.org", PASSWORD, "", "pilot", "", "").run();
        verify(users, never()).save(any());
        verify(workspaces, never()).save(any());
        verify(memberships, never()).save(any());
    }

    @Test
    void anEmptyInstallationWithoutOwnerConfigurationFailsWithNamesOnly() {
        when(users.count()).thenReturn(0L);
        assertThatThrownBy(() -> provisioning("", "", "", "", "", "").run()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FDM_OWNER_EMAIL").hasMessageContaining("FDM_OWNER_PASSWORD").hasMessageContaining("FDM_WORKSPACE_SLUG")
                .hasMessageContaining("values are never printed");
    }

    @Test
    void weakPlaceholderAndEmailDerivedPasswordsAreRefusedWithoutEchoingThem() {
        when(users.count()).thenReturn(0L);
        for (String weak : List.of("dev_owner_password_change_me", "short1!", "ownername-Strong-9921x")) {
            assertThatThrownBy(() -> provisioning("ownername@pilot.example.org", weak, "", "pilot", "", "").run())
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(weak);
        }
        verify(users, never()).save(any());
    }

    @Test
    void passwordCanComeFromAFileAndIsStripped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("owner-password");
        Files.writeString(file, PASSWORD + "\n");
        when(users.count()).thenReturn(0L);
        when(workspaces.save(any())).thenAnswer(i -> i.getArgument(0));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        provisioning("owner@pilot.example.org", "", file.toString(), "pilot", "", "").run();
        ArgumentCaptor<AppUser> user = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(user.capture());
        assertThat(encoder.matches(PASSWORD, user.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void unreadablePasswordFileFailsWithoutLeakingThePath() {
        when(users.count()).thenReturn(0L);
        assertThatThrownBy(() -> provisioning("owner@pilot.example.org", "", "/no/such/secret-file", "pilot", "", "").run())
                .hasMessageContaining("FDM_OWNER_PASSWORD_FILE cannot be read").hasMessageNotContaining("/no/such");
    }

    @Test
    void invalidSlugIsRefused() {
        when(users.count()).thenReturn(0L);
        assertThatThrownBy(() -> provisioning("owner@pilot.example.org", PASSWORD, "", "Not A Slug!", "", "").run()).hasMessageContaining("FDM_WORKSPACE_SLUG");
    }

    @Test
    void workerCredentialIsRegisteredOnceWithOnlyAHash() {
        Workspace workspace = new Workspace("W", "w");
        when(users.count()).thenReturn(1L);
        when(workspaces.findAll()).thenReturn(List.of(workspace));
        UUID id = UUID.randomUUID();
        when(credentials.existsById(id)).thenReturn(false);
        provisioning("", "", "", "", id.toString(), SECRET).run();
        ArgumentCaptor<WorkerCredential> saved = ArgumentCaptor.forClass(WorkerCredential.class);
        verify(credentials).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(id);
        assertThat(saved.getValue().getSecretHash()).isNotEqualTo(SECRET);
        assertThat(encoder.matches(SECRET, saved.getValue().getSecretHash())).isTrue();
        when(credentials.existsById(id)).thenReturn(true);
        provisioning("", "", "", "", id.toString(), SECRET).run();
        verify(credentials, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void weakOrMalformedWorkerCredentialsAreRefused() {
        when(users.count()).thenReturn(1L);
        when(workspaces.findAll()).thenReturn(List.of(new Workspace("W", "w")));
        assertThatThrownBy(() -> provisioning("", "", "", "", "not-a-uuid", SECRET).run()).hasMessageContaining("FDM_WORKER_CREDENTIAL_ID");
        assertThatThrownBy(() -> provisioning("", "", "", "", UUID.randomUUID().toString(), "dev_worker_secret_change_me").run())
                .hasMessageContaining("FDM_WORKER_CREDENTIAL_SECRET").hasMessageNotContaining("dev_worker_secret_change_me");
        verify(credentials, never()).save(any());
    }

    @Test
    void passwordPolicyIsDeterministic() {
        assertThat(InitialProvisioning.passwordProblems(PASSWORD, "owner@x.org", "P", 12)).isEmpty();
        assertThat(InitialProvisioning.passwordProblems("", "owner@x.org", "P", 12)).isNotEmpty();
        assertThat(InitialProvisioning.passwordProblems("Password-Strong-1234", "owner@x.org", "P", 12)).isNotEmpty();
    }
}
