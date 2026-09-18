package com.fdmultimedia.api.accounts;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SocialAccountService {

    private static final int MAX_DISPLAY_NAME_LENGTH = 100;

    private final AuthService authService;
    private final SocialAccountRepository accounts;
    private final Clock clock;

    public SocialAccountService(AuthService authService, SocialAccountRepository accounts, Clock clock) {
        this.authService = authService;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public SocialAccountSummary create(AuthenticatedUser principal, CreateSocialAccountRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        SocialPlatform platform = supportedPlatform(request.platform());
        String displayName = validateDisplayName(request.displayName());
        Instant now = Instant.now(clock);
        SocialAccount account = accounts.save(new SocialAccount(
                membership.getWorkspace(), platform, displayName, membership.getUser(), now));
        return toSummary(account);
    }

    @Transactional(readOnly = true)
    public List<SocialAccountSummary> listFor(AuthenticatedUser principal) {
        Workspace workspace = currentWorkspace(principal);
        return accounts.findByWorkspaceOrderByCreatedAtDesc(workspace).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public SocialAccountSummary getFor(AuthenticatedUser principal, UUID accountId) {
        Workspace workspace = currentWorkspace(principal);
        return accounts.findByWorkspaceAndId(workspace, accountId)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Social account not found"));
    }

    private SocialPlatform supportedPlatform(String requested) {
        SocialPlatform platform;
        try {
            platform = SocialPlatform.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown platform");
        }
        // Only TEST has a working PublishingProvider in Phase 10A. INSTAGRAM/TIKTOK
        // are reserved enum values for a future real provider (Phase 10B+) and must
        // never be exposed as creatable until that provider actually exists.
        if (platform != SocialPlatform.TEST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Platform is not yet supported");
        }
        return platform;
    }

    private String validateDisplayName(String displayName) {
        String trimmed = displayName == null ? "" : displayName.trim();
        if (trimmed.isBlank() || trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName must be 1-" + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private SocialAccountSummary toSummary(SocialAccount account) {
        return new SocialAccountSummary(
                account.getId(),
                account.getPlatform(),
                account.getDisplayName(),
                account.getExternalAccountId(),
                account.getStatus(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
