package com.fdmultimedia.api.publishing.tiktok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TikTokAccountConnectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final SocialAccountRepository socialAccounts = mock(SocialAccountRepository.class);
    private final SocialCredentialService credentialService = mock(SocialCredentialService.class);
    private final SocialOAuthStateService oauthStateService = mock(SocialOAuthStateService.class);
    private final TikTokApiClient api = mock(TikTokApiClient.class);
    private final TikTokProperties properties = configuredProperties();
    private final TikTokAccountConnectionService service = new TikTokAccountConnectionService(
            authService, socialAccounts, credentialService, oauthStateService, api, properties,
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
    void startRejectedWhenNotConfigured() {
        TikTokProperties unconfigured = new TikTokProperties();
        TikTokAccountConnectionService unconfiguredService = new TikTokAccountConnectionService(
                authService, socialAccounts, credentialService, oauthStateService, api, unconfigured,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> unconfiguredService.start(user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void startCreatesWorkspaceBoundStateAndReturnsTheAuthorizationUrl() {
        when(oauthStateService.create(workspace, owner, "TIKTOK", properties.getOauthStateTtl())).thenReturn("raw-state");
        when(api.authorizationUrl("raw-state")).thenReturn("https://www.tiktok.com/v2/auth/authorize/?state=raw-state");

        String url = service.start(user);

        assertThat(url).isEqualTo("https://www.tiktok.com/v2/auth/authorize/?state=raw-state");
    }

    @Test
    void successfulCallbackCreatesAccountKeyedByOpenIdAndStoresTokens() {
        when(oauthStateService.consume("raw-state", "TIKTOK")).thenReturn(validState());
        when(api.exchange("auth-code")).thenReturn(new TikTokModels.Token(
                "open-id-1", "access-token", "refresh-token", NOW.plusSeconds(3600), NOW.plusSeconds(86_400), "video.publish"));
        when(api.creatorInfo("access-token")).thenReturn(
                new TikTokModels.CreatorInfo("dragos", "Dragos", List.of("SELF_ONLY"), false, false, false, 600));
        when(socialAccounts.findByWorkspaceAndPlatformAndExternalAccountId(workspace, SocialPlatform.TIKTOK, "open-id-1"))
                .thenReturn(Optional.empty());

        TikTokCallbackResult result = service.callback("raw-state", "auth-code", null);

        assertThat(result.success()).isTrue();
        verify(credentialService).storeOAuthTokens(any(SocialAccount.class), eq("TIKTOK_OAUTH2"),
                eq("access-token"), eq("refresh-token"), eq(NOW.plusSeconds(3600)), eq(NOW.plusSeconds(86_400)), eq("video.publish"));
    }

    @Test
    void displayNameFallsBackToUsernameThenOpenIdWhenNicknameIsBlank() {
        when(oauthStateService.consume("raw-state", "TIKTOK")).thenReturn(validState());
        when(api.exchange("auth-code")).thenReturn(new TikTokModels.Token(
                "open-id-1", "access-token", "refresh-token", NOW.plusSeconds(3600), NOW.plusSeconds(86_400), "video.publish"));
        when(api.creatorInfo("access-token")).thenReturn(
                new TikTokModels.CreatorInfo("dragos_handle", "", List.of("SELF_ONLY"), false, false, false, 600));
        when(socialAccounts.findByWorkspaceAndPlatformAndExternalAccountId(workspace, SocialPlatform.TIKTOK, "open-id-1"))
                .thenReturn(Optional.empty());

        service.callback("raw-state", "auth-code", null);

        org.mockito.ArgumentCaptor<SocialAccount> saved = org.mockito.ArgumentCaptor.forClass(SocialAccount.class);
        verify(socialAccounts).save(saved.capture());
        assertThat(saved.getValue().getDisplayName()).isEqualTo("dragos_handle");
    }

    @Test
    void reconnectingSameOpenIdReactivatesExistingAccountInsteadOfDuplicating() {
        when(oauthStateService.consume("raw-state", "TIKTOK")).thenReturn(validState());
        when(api.exchange("auth-code")).thenReturn(new TikTokModels.Token(
                "open-id-1", "access-token", "refresh-token", NOW.plusSeconds(3600), NOW.plusSeconds(86_400), "video.publish"));
        when(api.creatorInfo("access-token")).thenReturn(
                new TikTokModels.CreatorInfo("dragos", "Dragos", List.of("SELF_ONLY"), false, false, false, 600));
        SocialAccount existing = new SocialAccount(workspace, SocialPlatform.TIKTOK, "old-name", "open-id-1", owner, NOW.minusSeconds(86_400));
        existing.markError(NOW.minusSeconds(3600));
        when(socialAccounts.findByWorkspaceAndPlatformAndExternalAccountId(workspace, SocialPlatform.TIKTOK, "open-id-1"))
                .thenReturn(Optional.of(existing));

        TikTokCallbackResult result = service.callback("raw-state", "auth-code", null);

        assertThat(result.success()).isTrue();
        assertThat(existing.getDisplayName()).isEqualTo("Dragos");
        assertThat(existing.getStatus()).isEqualTo(SocialAccountStatus.ACTIVE);
        verify(socialAccounts, never()).save(any(SocialAccount.class));
    }

    @Test
    void callbackWithoutVideoPublishScopeFailsWithoutStoringAnyCredential() {
        when(oauthStateService.consume("raw-state", "TIKTOK")).thenReturn(validState());
        when(api.exchange("auth-code")).thenReturn(new TikTokModels.Token(
                "open-id-1", "access-token", "refresh-token", NOW.plusSeconds(3600), NOW.plusSeconds(86_400), "user.info.basic"));

        TikTokCallbackResult result = service.callback("raw-state", "auth-code", null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("missing_scope");
        verify(credentialService, never()).storeOAuthTokens(any(), any(), any(), any(), any(), any(), any());
        verify(api, never()).creatorInfo(any());
    }

    @Test
    void providerDenialProducesSafeFailureWithoutConsumingState() {
        TikTokCallbackResult result = service.callback("raw-state", null, "access_denied");

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("denied");
        verify(oauthStateService, never()).consume(any(), any());
    }

    @Test
    void invalidStateProducesSafeFailureWithoutExchangingCode() {
        when(oauthStateService.consume("bad-state", "TIKTOK")).thenThrow(new InvalidOAuthStateException("bad"));

        TikTokCallbackResult result = service.callback("bad-state", "auth-code", null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("invalid_state");
        verify(api, never()).exchange(any());
    }

    @Test
    void missingCodeAfterValidStateProducesSafeFailure() {
        when(oauthStateService.consume("raw-state", "TIKTOK")).thenReturn(validState());

        TikTokCallbackResult result = service.callback("raw-state", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("missing_code");
    }

    @Test
    void exchangeFailureProducesSafeGenericFailureNotRawProviderDetails() {
        when(oauthStateService.consume("raw-state", "TIKTOK")).thenReturn(validState());
        when(api.exchange("auth-code")).thenThrow(new TikTokApiException("PROVIDER_TEMPORARY", "raw provider detail that must not leak", true));

        TikTokCallbackResult result = service.callback("raw-state", "auth-code", null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("exchange_failed");
    }

    @Test
    void disconnectRemovesCredentialAndMarksDisconnectedWithoutDeletingHistory() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TIKTOK, "creator", "open-id-1", owner, NOW.minusSeconds(3600));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));

        SocialAccountSummary summary = service.disconnect(user, account.getId());

        assertThat(summary.status()).isEqualTo(SocialAccountStatus.DISCONNECTED);
        verify(credentialService).remove(account);
    }

    @Test
    void disconnectRejectsNonTikTokAccounts() {
        SocialAccount testAccount = new SocialAccount(workspace, SocialPlatform.TEST, "test account", owner, NOW);
        when(socialAccounts.findByWorkspaceAndId(workspace, testAccount.getId())).thenReturn(Optional.of(testAccount));

        assertThatThrownBy(() -> service.disconnect(user, testAccount.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void capabilitiesRejectsAnInactiveAccountWithoutCallingTikTok() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TIKTOK, "creator", "open-id-1", owner, NOW.minusSeconds(3600));
        account.markError(NOW);
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.capabilities(user, account.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
        verify(api, never()).creatorInfo(any());
    }

    @Test
    void capabilitiesQueriesFreshCreatorInfoRatherThanAnyCache() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TIKTOK, "creator", "open-id-1", owner, NOW.minusSeconds(3600));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));
        when(credentialService.decryptOAuthCredential(account)).thenReturn(new SocialCredentialService.OAuthCredential(
                "access-token", "refresh-token", NOW.plusSeconds(3600), NOW.plusSeconds(86_400), "video.publish"));
        TikTokModels.CreatorInfo info = new TikTokModels.CreatorInfo("dragos", "Dragos", List.of("SELF_ONLY"), false, false, false, 600);
        when(api.creatorInfo("access-token")).thenReturn(info);

        TikTokModels.CreatorInfo result = service.capabilities(user, account.getId());

        assertThat(result).isEqualTo(info);
    }

    @Test
    void validAccessTokenReusesUnexpiredTokenWithoutRefreshing() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TIKTOK, "creator", "open-id-1", owner, NOW.minusSeconds(3600));
        when(credentialService.decryptOAuthCredential(account)).thenReturn(new SocialCredentialService.OAuthCredential(
                "still-valid-access-token", "refresh-token", NOW.plus(properties.getRefreshMargin()).plusSeconds(120),
                NOW.plusSeconds(86_400), "video.publish"));

        String token = service.validAccessToken(account);

        assertThat(token).isEqualTo("still-valid-access-token");
        verify(api, never()).refresh(any());
    }

    @Test
    void validAccessTokenRefreshesWithinTheSafetyMarginAndPersistsRotatedTokens() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TIKTOK, "creator", "open-id-1", owner, NOW.minusSeconds(3600));
        when(credentialService.decryptOAuthCredential(account)).thenReturn(new SocialCredentialService.OAuthCredential(
                "about-to-expire", "refresh-token", NOW.plusSeconds(60), NOW.plusSeconds(86_400), "video.publish"));
        when(api.refresh("refresh-token")).thenReturn(new TikTokModels.Token(
                "open-id-1", "rotated-access-token", "rotated-refresh-token", NOW.plusSeconds(3600), NOW.plusSeconds(90_000), "video.publish"));

        String token = service.validAccessToken(account);

        assertThat(token).isEqualTo("rotated-access-token");
        verify(credentialService).storeOAuthTokens(eq(account), eq("TIKTOK_OAUTH2"), eq("rotated-access-token"),
                eq("rotated-refresh-token"), eq(NOW.plusSeconds(3600)), eq(NOW.plusSeconds(90_000)), eq("video.publish"));
    }

    @Test
    void refreshThatLosesVideoPublishScopeMarksAccountInErrorRatherThanCachingAToken() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TIKTOK, "creator", "open-id-1", owner, NOW.minusSeconds(3600));
        when(credentialService.decryptOAuthCredential(account)).thenReturn(new SocialCredentialService.OAuthCredential(
                "about-to-expire", "refresh-token", NOW.plusSeconds(60), NOW.plusSeconds(86_400), "video.publish"));
        when(api.refresh("refresh-token")).thenReturn(new TikTokModels.Token(
                "open-id-1", "rotated-access-token", "rotated-refresh-token", NOW.plusSeconds(3600), NOW.plusSeconds(90_000), "user.info.basic"));

        assertThatThrownBy(() -> service.validAccessToken(account))
                .isInstanceOf(TikTokApiException.class)
                .extracting("code")
                .isEqualTo("SCOPE_NOT_AUTHORIZED");
        assertThat(account.getStatus()).isEqualTo(SocialAccountStatus.ERROR);
        verify(credentialService, never()).storeOAuthTokens(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refreshFailureMarksAccountInErrorSoUiCanPromptReconnect() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TIKTOK, "creator", "open-id-1", owner, NOW.minusSeconds(3600));
        when(credentialService.decryptOAuthCredential(account)).thenReturn(new SocialCredentialService.OAuthCredential(
                "about-to-expire", "refresh-token", NOW.plusSeconds(60), NOW.plusSeconds(86_400), "video.publish"));
        when(api.refresh("refresh-token")).thenThrow(new TikTokApiException("AUTH_REAUTH_REQUIRED", "refresh token invalid", false));

        assertThatThrownBy(() -> service.validAccessToken(account)).isInstanceOf(TikTokApiException.class);
        assertThat(account.getStatus()).isEqualTo(SocialAccountStatus.ERROR);
    }

    private SocialOAuthState validState() {
        return new SocialOAuthState("state-hash", "TIKTOK", workspace, owner, NOW.plusSeconds(600), NOW);
    }

    private TikTokProperties configuredProperties() {
        TikTokProperties props = new TikTokProperties();
        props.setEnabled(true);
        props.setClientKey("client-key");
        props.setClientSecret("client-secret");
        props.setOauthRedirectUri("https://app.example.test/api/social-accounts/tiktok/callback");
        return props;
    }
}
