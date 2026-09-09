package com.fdmultimedia.api.workers.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.workers.WorkerCredential;
import com.fdmultimedia.api.workers.WorkerCredentialRepository;
import com.fdmultimedia.api.workspaces.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class WorkerAuthenticationServiceTest {

    private final WorkerCredentialRepository credentials = mock(WorkerCredentialRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final WorkerAuthenticationService service = new WorkerAuthenticationService(credentials, passwordEncoder);

    @Test
    void authenticatesValidWorkerTokenWithoutExposingSecret() {
        Workspace workspace = new Workspace("FD Multimedia", "fd-multimedia");
        UUID credentialId = UUID.randomUUID();
        WorkerCredential credential = new WorkerCredential(
                credentialId,
                workspace,
                "local-agent",
                passwordEncoder.encode("machine-secret"));
        when(credentials.findById(credentialId)).thenReturn(Optional.of(credential));

        Optional<WorkerPrincipal> principal = service.authenticate("WorkerToken " + credentialId + ".machine-secret");

        assertThat(principal).isPresent();
        assertThat(principal.get().credentialId()).isEqualTo(credentialId);
        assertThat(principal.get().workspaceId()).isEqualTo(workspace.getId());
        assertThat(principal.get().name()).isEqualTo("local-agent");
    }

    @Test
    void rejectsInvalidWorkerToken() {
        UUID credentialId = UUID.randomUUID();
        WorkerCredential credential = new WorkerCredential(
                credentialId,
                new Workspace("FD Multimedia", "fd-multimedia"),
                "local-agent",
                passwordEncoder.encode("machine-secret"));
        when(credentials.findById(credentialId)).thenReturn(Optional.of(credential));

        Optional<WorkerPrincipal> principal = service.authenticate("WorkerToken " + credentialId + ".wrong-secret");

        assertThat(principal).isEmpty();
    }

    @Test
    void rejectsMalformedAuthorizationHeaders() {
        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate("Bearer abc")).isEmpty();
        assertThat(service.authenticate("WorkerToken not-a-uuid.secret")).isEmpty();
        assertThat(service.authenticate("WorkerToken " + UUID.randomUUID())).isEmpty();
    }
}
