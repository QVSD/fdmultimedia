package com.fdmultimedia.api.workers.security;

import com.fdmultimedia.api.workers.WorkerCredential;
import java.util.UUID;

public record WorkerPrincipal(UUID credentialId, UUID workspaceId, String name) {

    public WorkerPrincipal(WorkerCredential credential) {
        this(credential.getId(), credential.getWorkspace().getId(), credential.getName());
    }
}
