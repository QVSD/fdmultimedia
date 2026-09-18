package com.fdmultimedia.api.publishing.instagram;

import com.fdmultimedia.api.accounts.InvalidOAuthStateException;
import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountRepository;
import com.fdmultimedia.api.accounts.SocialAccountSummary;
import com.fdmultimedia.api.accounts.SocialCredentialService;
import com.fdmultimedia.api.accounts.SocialOAuthState;
import com.fdmultimedia.api.accounts.SocialOAuthStateService;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates the Instagram Login OAuth flow end to end: start (build the
 * official authorization URL behind a fresh, server-tracked state token) and
 * callback (validate that state, exchange the authorization code server-side,
 * discover the authorized Instagram account, and persist the SocialAccount +
 * encrypted credential). The authorization code and short-lived token never
 * leave this service; the long-lived token is handed to
 * {@link com.fdmultimedia.api.accounts.SocialCredentialService} for
 * encryption immediately and is not retained in memory afterward.
 */
@Service
public class InstagramAccountConnectionService {

    private static final String PLATFORM = "INSTAGRAM";

    private final AuthService authService;
    private final SocialAccountRepository socialAccounts;
    private final SocialCredentialService credentialService;
    private final SocialOAuthStateService oauthStateService;
    private final InstagramGraphClient graphClient;
    private final InstagramProperties properties;
    private final Clock clock;

    public InstagramAccountConnectionService(
            AuthService authService,
            SocialAccountRepository socialAccounts,
            SocialCredentialService credentialService,
            SocialOAuthStateService oauthStateService,
            InstagramGraphClient graphClient,
            InstagramProperties properties,
            Clock clock) {
        this.authService = authService;
        this.socialAccounts = socialAccounts;
        this.credentialService = credentialService;
        this.oauthStateService = oauthStateService;
        this.graphClient = graphClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public String startAuthorization(AuthenticatedUser principal) {
        requireConfigured();
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        String rawState = oauthStateService.create(
                membership.getWorkspace(), membership.getUser(), PLATFORM, properties.getOauthStateTtl());
        return graphClient.authorizationUrl(rawState);
    }

    /**
     * Handles the OAuth callback. Never throws for provider-supplied error
     * conditions (invalid state, denied consent, exchange failure) — callers
     * get a {@link InstagramCallbackResult} to turn into a safe redirect
     * instead of an exception leaking provider details to the browser.
     */
    @Transactional
    public InstagramCallbackResult handleCallback(String rawState, String code, String providerError) {
        if (providerError != null && !providerError.isBlank()) {
            return InstagramCallbackResult.failure("denied");
        }
        SocialOAuthState state;
        try {
            state = oauthStateService.consume(rawState, PLATFORM);
        } catch (InvalidOAuthStateException ex) {
            return InstagramCallbackResult.failure("invalid_state");
        }
        if (code == null || code.isBlank()) {
            return InstagramCallbackResult.failure("missing_code");
        }
        // Workspace/user context comes only from the server-side state row
        // created when this user started the flow — never from callback
        // query parameters, which are attacker-controlled.
        Workspace workspace = state.getWorkspace();

        try {
            InstagramTokenResult shortLived = graphClient.exchangeCodeForShortLivedToken(code);
            InstagramTokenResult longLived = graphClient.exchangeForLongLivedToken(shortLived.accessToken());
            // "me" is the standard Graph API alias for "the entity identified by
            // this access token." Meta's current docs for the Instagram Login
            // flow do not publish a separate multi-account discovery endpoint
            // (a documented research gap, see docs/ARCHITECTURE.md); the token
            // exchange grants access to exactly the one account the user
            // authorized, so this single lookup is the account discovery step.
            InstagramAccountProfile profile = graphClient.fetchAccountProfile("me", longLived.accessToken());

            SocialAccount account = socialAccounts
                    .findByWorkspaceAndPlatformAndExternalAccountId(workspace, SocialPlatform.INSTAGRAM, profile.id())
                    .map(existing -> {
                        existing.reactivate(profile.username(), Instant.now(clock));
                        return existing;
                    })
                    .orElseGet(() -> socialAccounts.save(new SocialAccount(
                            workspace, SocialPlatform.INSTAGRAM, profile.username(), profile.id(), state.getUser(), Instant.now(clock))));

            credentialService.store(account, "INSTAGRAM_OAUTH2", longLived.accessToken(), longLived.expiresAt(), requiredScopes());
            return InstagramCallbackResult.success(toSummary(account));
        } catch (InstagramApiException ex) {
            return InstagramCallbackResult.failure("exchange_failed");
        }
    }

    @Transactional
    public SocialAccountSummary disconnect(AuthenticatedUser principal, java.util.UUID accountId) {
        Workspace workspace = authService.currentMembershipFor(principal).getWorkspace();
        SocialAccount account = socialAccounts.findByWorkspaceAndId(workspace, accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Social account not found"));
        if (account.getPlatform() != SocialPlatform.INSTAGRAM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only Instagram accounts support disconnect");
        }
        // Meta does not currently offer an app-callable revoke endpoint scoped
        // to the Instagram Login flow's short-lived/long-lived tokens; this
        // removes our own stored copy of the credential so it can no longer be
        // used for publishing. It does not assert that the grant was revoked
        // on Meta's side — the user can also revoke from their own Instagram
        // account settings.
        credentialService.remove(account);
        account.markDisconnected(Instant.now(clock));
        return toSummary(account);
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instagram integration is not configured");
        }
    }

    private String requiredScopes() {
        return "instagram_business_basic,instagram_business_content_publish";
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
