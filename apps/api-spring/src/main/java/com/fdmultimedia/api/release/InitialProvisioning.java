package com.fdmultimedia.api.release;

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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one supported way to create the first account of a production or pilot installation (Phase 17R). It is idempotent and
 * deliberately small: when the database has no users it creates the first workspace and its OWNER from {@code FDM_OWNER_*}; it can
 * also register one Worker credential from {@code FDM_WORKER_CREDENTIAL_*} (the API stores only a BCrypt hash). Secrets may be given
 * directly or, preferably, as a file path ({@code *_FILE}, for Docker/Kubernetes secrets). Nothing here is active in the dev profile,
 * which keeps its own bootstrap, and no value is ever logged.
 */
@Component
@Profile("!dev")
@Order(10)
public class InitialProvisioning implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(InitialProvisioning.class);
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final int MIN_PASSWORD = 12;
    private static final int MIN_WORKER_SECRET = 24;
    private static final List<String> BLOCKED = List.of("change_me", "changeme", "password", "dev_", "example", "qwerty", "12345678");

    private final AppUserRepository users;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMembershipRepository memberships;
    private final WorkerCredentialRepository workerCredentials;
    private final PasswordEncoder encoder;
    private final EmailNormalizer emails;
    private final String ownerEmail;
    private final String ownerName;
    private final String ownerPassword;
    private final String ownerPasswordFile;
    private final String workspaceName;
    private final String workspaceSlug;
    private final String workerId;
    private final String workerName;
    private final String workerSecret;
    private final String workerSecretFile;

    public InitialProvisioning(AppUserRepository users, WorkspaceRepository workspaces, WorkspaceMembershipRepository memberships,
            WorkerCredentialRepository workerCredentials, PasswordEncoder encoder, EmailNormalizer emails,
            @Value("${app.provisioning.owner-email:}") String ownerEmail,
            @Value("${app.provisioning.owner-display-name:}") String ownerName,
            @Value("${app.provisioning.owner-password:}") String ownerPassword,
            @Value("${app.provisioning.owner-password-file:}") String ownerPasswordFile,
            @Value("${app.provisioning.workspace-name:}") String workspaceName,
            @Value("${app.provisioning.workspace-slug:}") String workspaceSlug,
            @Value("${app.provisioning.worker-credential-id:}") String workerId,
            @Value("${app.provisioning.worker-credential-name:}") String workerName,
            @Value("${app.provisioning.worker-credential-secret:}") String workerSecret,
            @Value("${app.provisioning.worker-credential-secret-file:}") String workerSecretFile) {
        this.users = users; this.workspaces = workspaces; this.memberships = memberships; this.workerCredentials = workerCredentials;
        this.encoder = encoder; this.emails = emails;
        this.ownerEmail = ownerEmail; this.ownerName = ownerName; this.ownerPassword = ownerPassword; this.ownerPasswordFile = ownerPasswordFile;
        this.workspaceName = workspaceName; this.workspaceSlug = workspaceSlug;
        this.workerId = workerId; this.workerName = workerName; this.workerSecret = workerSecret; this.workerSecretFile = workerSecretFile;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Workspace workspace;
        if (users.count() == 0) {
            workspace = createFirstOwner();
        } else {
            if (!ownerEmail.isBlank() || !ownerPassword.isBlank() || !ownerPasswordFile.isBlank()) {
                log.info("Initial owner configuration ignored: the installation already has users");
            }
            workspace = soleWorkspace();
        }
        registerWorkerCredential(workspace);
    }

    private Workspace createFirstOwner() {
        List<String> problems = new ArrayList<>();
        String email = emails.normalize(ownerEmail);
        String password = secret(ownerPassword, ownerPasswordFile, "FDM_OWNER_PASSWORD", problems);
        if (!EMAIL.matcher(email).matches()) problems.add("FDM_OWNER_EMAIL must be a valid e-mail address");
        if (ownerName.isBlank()) problems.add("FDM_OWNER_NAME is required");
        if (workspaceName.isBlank()) problems.add("FDM_WORKSPACE_NAME is required");
        if (!SLUG.matcher(workspaceSlug).matches()) problems.add("FDM_WORKSPACE_SLUG must be 1-64 lower-case letters, digits or hyphens");
        problems.addAll(passwordProblems(password, email, "FDM_OWNER_PASSWORD", MIN_PASSWORD));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The database has no users and the first owner cannot be created (" + problems.size()
                    + " problem(s); values are never printed):\n - " + String.join("\n - ", problems) + "\nSee docs/CONFIGURATION.md.");
        }
        Workspace workspace = workspaces.save(new Workspace(workspaceName.trim(), workspaceSlug.trim()));
        AppUser owner = users.save(new AppUser(email, encoder.encode(password), ownerName.trim()));
        memberships.save(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        log.info("Initial provisioning created workspace {} and its owner", workspace.getSlug());
        return workspace;
    }

    private Workspace soleWorkspace() {
        List<Workspace> all = workspaces.findAll();
        return all.size() == 1 ? all.get(0) : null;
    }

    private void registerWorkerCredential(Workspace workspace) {
        if (workerId.isBlank() && workerSecret.isBlank() && workerSecretFile.isBlank()) return;
        List<String> problems = new ArrayList<>();
        UUID id = null;
        try {
            id = UUID.fromString(workerId.trim());
        } catch (IllegalArgumentException ex) {
            problems.add("FDM_WORKER_CREDENTIAL_ID must be a UUID");
        }
        String secret = secret(workerSecret, workerSecretFile, "FDM_WORKER_CREDENTIAL_SECRET", problems);
        problems.addAll(passwordProblems(secret, "", "FDM_WORKER_CREDENTIAL_SECRET", MIN_WORKER_SECRET));
        if (workspace == null) problems.add("a Worker credential needs exactly one workspace; this installation has several");
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Worker credential cannot be registered (" + problems.size() + " problem(s); values are never printed):\n - "
                    + String.join("\n - ", problems));
        }
        if (workerCredentials.existsById(id)) {
            log.info("Worker credential {} already registered", id);
            return;
        }
        String name = workerName.isBlank() ? "pilot-worker" : workerName.trim();
        workerCredentials.save(new WorkerCredential(id, workspace, name, encoder.encode(secret)));
        log.info("Worker credential {} registered for workspace {}", id, workspace.getSlug());
    }

    private static String secret(String direct, String file, String name, List<String> problems) {
        if (!file.isBlank()) {
            try {
                return Files.readString(Path.of(file.trim()), StandardCharsets.UTF_8).strip();
            } catch (IOException | RuntimeException ex) {
                problems.add(name + "_FILE cannot be read");
                return "";
            }
        }
        return direct.strip();
    }

    /** Names and reasons only; the candidate value is never echoed. */
    static List<String> passwordProblems(String value, String email, String name, int minLength) {
        List<String> problems = new ArrayList<>();
        if (value == null || value.isBlank()) {
            problems.add(name + " (or " + name + "_FILE) is required");
            return problems;
        }
        if (value.length() < minLength) problems.add(name + " must be at least " + minLength + " characters");
        String lower = value.toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED) {
            if (lower.contains(blocked)) {
                problems.add(name + " looks like a placeholder or a common password");
                break;
            }
        }
        if (!email.isBlank()) {
            String local = email.substring(0, Math.max(0, email.indexOf('@'))).toLowerCase(Locale.ROOT);
            if (!local.isBlank() && lower.contains(local)) problems.add(name + " must not contain the e-mail name");
        }
        return problems;
    }
}
