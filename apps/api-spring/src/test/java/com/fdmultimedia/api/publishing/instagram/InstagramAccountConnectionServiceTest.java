package com.fdmultimedia.api.publishing.instagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.InvalidOAuthStateException;
import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountRepository;
import com.fdmultimedia.api.accounts.SocialAccountStatus;
import com.fdmultimedia.api.accounts.SocialAccountSummary;
import com.fdmultimedia.api.accounts.SocialCredentialService;
import com.fdmultimedia.api.accounts.SocialOAuthState;
import com.fdmultimedia.api.accounts.SocialOAuthStateService;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class InstagramAccountConnectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final SocialAccountRepository socialAccounts = mock(SocialAccountRepository.class);
    private final SocialCredentialService credentialService = mock(SocialCredentialService.class);
    private final SocialOAuthStateService oauthStateService = mock(SocialOAuthStateService.class);
    private final InstagramGraphClient graphClient = mock(InstagramGraphClient.class);
    private final InstagramProperties properties = configuredProperties();
    private final InstagramAccountConnectionService service = new InstagramAccountConnectionService(
            authService, socialAccounts, credentialService, oauthStateService, graphClient, properties,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        when(socialAccounts.save(any(SocialAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void startAuthorizationCreatesStateAndReturnsAuthorizationUrl() {
        when(oauthStateService.create(workspace, owner, "INSTAGRAM", properties.getOauthStateTtl())).thenReturn("raw-state");
        when(graphClient.authorizationUrl("raw-state")).thenReturn("https://www.instagram.com/oauth/authorize?state=raw-state");

        String url = service.startAuthorization(user);

        assertThat(url).isEqualTo("https://www.instagram.com/oauth/authorize?state=raw-state");
    }

    @Test
    void startAuthorizationRejectedWhenNotConfigured() {
        InstagramProperties unconfigured = new InstagramProperties();
        InstagramAccountConnectionService unconfiguredService = new InstagramAccountConnectionService(
                authService, socialAccounts, credentialService, oauthStateService, graphClient, unconfigured,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> unconfiguredService.startAuthorization(user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void successfulCallbackCreatesSocialAccountAndStoresCredential() {
        SocialOAuthState state = validState();
        when(oauthStateService.consume("raw-state", "INSTAGRAM")).thenReturn(state);
        when(graphClient.exchangeCodeForShortLivedToken("auth-code")).thenReturn(new InstagramTokenResult("short-token", NOW.plusSeconds(3600)));
        when(graphClient.exchangeForLongLivedToken("short-token")).thenReturn(new InstagramTokenResult("long-token", NOW.plusSeconds(5_184_000)));
        when(graphClient.fetchAccountProfile("me", "long-token")).thenReturn(new InstagramAccountProfile("ig-user-1", "creator_handle"));
        when(socialAccounts.findByWorkspaceAndPlatformAndExternalAccountId(workspace, SocialPlatform.INSTAGRAM, "ig-user-1"))
                .thenReturn(Optional.empty());

        InstagramCallbackResult result = service.handleCallback("raw-state", "auth-code", null);

        assertThat(result.success()).isTrue();
        assertThat(result.account().displayName()).isEqualTo("creator_handle");
        assertThat(result.account().externalAccountId()).isEqualTo("ig-user-1");
        assertThat(result.account().platform()).isEqualTo(SocialPlatform.INSTAGRAM);
        verify(credentialService).store(any(SocialAccount.class), org.mockito.ArgumentMatchers.eq("INSTAGRAM_OAUTH2"),
                org.mockito.ArgumentMatchers.eq("long-token"), org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(5_184_000)), any());
    }

    @Test
    void reconnectingSameExternalAccountUpdatesExistingRowInsteadOfDuplicating() {
        SocialOAuthState state = validState();
        when(oauthStateService.consume("raw-state", "INSTAGRAM")).thenReturn(state);
        when(graphClient.exchangeCodeForShortLivedToken("auth-code")).thenReturn(new InstagramTokenResult("short-token", NOW.plusSeconds(3600)));
        when(graphClient.exchangeForLongLivedToken("short-token")).thenReturn(new InstagramTokenResult("long-token", NOW.plusSeconds(5_184_000)));
        when(graphClient.fetchAccountProfile("me", "long-token")).thenReturn(new InstagramAccountProfile("ig-user-1", "creator_handle"));
        SocialAccount existing = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "old_handle", "ig-user-1", owner, NOW.minusSeconds(86400));
        when(socialAccounts.findByWorkspaceAndPlatformAndExternalAccountId(workspace, SocialPlatform.INSTAGRAM, "ig-user-1"))
                .thenReturn(Optional.of(existing));

        InstagramCallbackResult result = service.handleCallback("raw-state", "auth-code", null);

        assertThat(result.success()).isTrue();
        assertThat(existing.getDisplayName()).isEqualTo("creator_handle");
        assertThat(existing.getStatus()).isEqualTo(SocialAccountStatus.ACTIVE);
        verify(socialAccounts, never()).save(any(SocialAccount.class));
    }

    @Test
    void invalidStateProducesSafeFailureWithoutExchangingCode() {
        when(oauthStateService.consume("bad-state", "INSTAGRAM")).thenThrow(new InvalidOAuthStateException("bad"));

        InstagramCallbackResult result = service.handleCallback("bad-state", "auth-code", null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("invalid_state");
        verify(graphClient, never()).exchangeCodeForShortLivedToken(any());
    }

    @Test
    void providerDenialProducesSafeFailureWithoutConsumingState() {
        InstagramCallbackResult result = service.handleCallback("raw-state", null, "access_denied");

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("denied");
        verify(oauthStateService, never()).consume(any(), any());
    }

    @Test
    void missingCodeAfterValidStateProducesSafeFailure() {
        when(oauthStateService.consume("raw-state", "INSTAGRAM")).thenReturn(validState());

        InstagramCallbackResult result = service.handleCallback("raw-state", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("missing_code");
    }

    @Test
    void exchangeFailureProducesSafeFailureNotRawProviderDetails() {
        when(oauthStateService.consume("raw-state", "INSTAGRAM")).thenReturn(validState());
        when(graphClient.exchangeCodeForShortLivedToken("auth-code"))
                .thenThrow(new InstagramApiException(InstagramErrorCodes.TEMPORARY_ERROR, "raw provider detail that must not leak", true));

        InstagramCallbackResult result = service.handleCallback("raw-state", "auth-code", null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("exchange_failed");
    }

    @Test
    void disconnectRemovesCredentialAndMarksDisconnected() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW.minusSeconds(3600));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));

        SocialAccountSummary summary = service.disconnect(user, account.getId());

        assertThat(summary.status()).isEqualTo(SocialAccountStatus.DISCONNECTED);
        verify(credentialService).remove(account);
    }

    @Test
    void disconnectRejectsNonInstagramAccounts() {
        SocialAccount testAccount = new SocialAccount(workspace, SocialPlatform.TEST, "test account", owner, NOW);
        when(socialAccounts.findByWorkspaceAndId(workspace, testAccount.getId())).thenReturn(Optional.of(testAccount));

        assertThatThrownBy(() -> service.disconnect(user, testAccount.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void disconnectRejectsAccountFromAnotherWorkspace() {
        UUID otherAccountId = UUID.randomUUID();
        when(socialAccounts.findByWorkspaceAndId(workspace, otherAccountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.disconnect(user, otherAccountId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private SocialOAuthState validState() {
        return new SocialOAuthState("state-hash", "INSTAGRAM", workspace, owner, NOW.plusSeconds(600), NOW);
    }

    private InstagramProperties configuredProperties() {
        InstagramProperties props = new InstagramProperties();
        props.setEnabled(true);
        props.setAppId("app-id");
        props.setAppSecret("app-secret");
        props.setOauthRedirectUri("https://app.example.test/api/social-accounts/instagram/callback");
        props.setPublicBaseUrl("https://app.example.test");
        return props;
    }
}
