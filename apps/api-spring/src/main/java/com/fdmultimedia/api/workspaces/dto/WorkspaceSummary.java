package com.fdmultimedia.api.workspaces.dto;

import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.util.UUID;

public record WorkspaceSummary(
        UUID id,
        String name,
        String slug,
        WorkspaceRole role) {
}
