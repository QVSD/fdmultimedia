package com.fdmultimedia.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SocialAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final SocialAccountRepository accounts = mock(SocialAccountRepository.class);
    private final SocialAccountService service = new SocialAccountService(authService, accounts, Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        when(accounts.save(any(SocialAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsTestSocialAccount() {
        SocialAccountSummary summary = service.create(user, new CreateSocialAccountRequest("TEST", "My TEST Account"));

        assertThat(summary.platform()).isEqualTo(SocialPlatform.TEST);
        assertThat(summary.displayName()).isEqualTo("My TEST Account");
        assertThat(summary.status()).isEqualTo(SocialAccountStatus.ACTIVE);
        assertThat(summary.externalAccountId()).isNull();
    }

    @Test
    void rejectsUnsupportedInstagramPlatform() {
        assertThatThrownBy(() -> service.create(user, new CreateSocialAccountRequest("INSTAGRAM", "My Instagram")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUnsupportedTiktokPlatform() {
        assertThatThrownBy(() -> service.create(user, new CreateSocialAccountRequest("TIKTOK", "My TikTok")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUnknownPlatformValue() {
        assertThatThrownBy(() -> service.create(user, new CreateSocialAccountRequest("BLUESKY", "My Account")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsBlankDisplayName() {
        assertThatThrownBy(() -> service.create(user, new CreateSocialAccountRequest("TEST", "   ")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsDisplayNameOverMaxLength() {
        String tooLong = "a".repeat(101);

        assertThatThrownBy(() -> service.create(user, new CreateSocialAccountRequest("TEST", tooLong)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listsAccountsForCurrentWorkspace() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TEST, "My TEST Account", owner, NOW);
        when(accounts.findByWorkspaceOrderByCreatedAtDesc(workspace)).thenReturn(List.of(account));

        List<SocialAccountSummary> result = service.listFor(user);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(account.getId());
    }

    @Test
    void getForReturnsNotFoundOutsideWorkspace() {
        UUID accountId = UUID.randomUUID();
        when(accounts.findByWorkspaceAndId(workspace, accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFor(user, accountId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
