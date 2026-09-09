package com.fdmultimedia.api.auth.dto;

import com.fdmultimedia.api.workspaces.dto.WorkspaceSummary;

public record AuthSessionResponse(
        UserSummary user,
        WorkspaceSummary currentWorkspace) {
}
