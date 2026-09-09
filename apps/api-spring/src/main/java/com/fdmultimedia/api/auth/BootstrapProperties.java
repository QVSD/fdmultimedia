package com.fdmultimedia.api.auth;

import com.fdmultimedia.api.workspaces.WorkspaceRole;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.bootstrap")
public class BootstrapProperties {

    private final Admin admin = new Admin();
    private final Workspace workspace = new Workspace();
    private final SecondUser secondUser = new SecondUser();
    private final WorkerCredential workerCredential = new WorkerCredential();

    public Admin getAdmin() {
        return admin;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public SecondUser getSecondUser() {
        return secondUser;
    }

    public WorkerCredential getWorkerCredential() {
        return workerCredential;
    }

    public static class Admin {
        private String email = "";
        private String password = "";
        private String displayName = "";

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }

    public static class Workspace {
        private String name = "";
        private String slug = "";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSlug() {
            return slug;
        }

        public void setSlug(String slug) {
            this.slug = slug;
        }
    }

    public static class SecondUser extends Admin {
        private WorkspaceRole role = WorkspaceRole.ADMIN;

        public WorkspaceRole getRole() {
            return role;
        }

        public void setRole(WorkspaceRole role) {
            this.role = role;
        }
    }

    public static class WorkerCredential {
        private String id = "";
        private String name = "";
        private String secret = "";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }
}
