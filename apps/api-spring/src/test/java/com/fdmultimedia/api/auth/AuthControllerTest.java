package com.fdmultimedia.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.dto.AuthSessionResponse;
import com.fdmultimedia.api.auth.dto.LoginRequest;
import com.fdmultimedia.api.auth.dto.UserSummary;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.users.EmailNormalizer;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import com.fdmultimedia.api.workspaces.dto.WorkspaceSummary;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class AuthControllerTest {

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuthController controller = new AuthController(
            authenticationManager,
            authService,
            new EmailNormalizer());

    @Test
    void loginSucceedsWithValidCredentialsAndCreatesSession() {
        AppUser appUser = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        AuthenticatedUser principal = new AuthenticatedUser(appUser);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        AuthSessionResponse response = sessionResponse(appUser.getId(), "owner@example.com", "Owner");

        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(authService.sessionFor(principal)).thenReturn(response);

        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthSessionResponse actual = controller.login(
                new LoginRequest(" OWNER@EXAMPLE.COM ", "secret"),
                request);

        assertThat(actual).isEqualTo(response);
        assertThat(request.getSession(false)).isNotNull();
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    void loginFailsWithGenericMessageForInvalidCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("raw provider detail"));

        try {
            controller.login(new LoginRequest("owner@example.com", "wrong"), mock(HttpServletRequest.class));
        } catch (ResponseStatusException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(ex.getReason()).isEqualTo("Invalid email or password");
            return;
        }

        throw new AssertionError("Expected ResponseStatusException");
    }

    @Test
    void sessionResponseDoesNotContainPasswordHash() {
        AuthSessionResponse response = sessionResponse(UUID.randomUUID(), "owner@example.com", "Owner");

        assertThat(response.user()).isInstanceOf(UserSummary.class);
        assertThat(response.user().email()).isEqualTo("owner@example.com");
    }

    @Test
    void meRejectsUnauthenticatedRequests() {
        try {
            controller.me(null);
        } catch (ResponseStatusException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            return;
        }

        throw new AssertionError("Expected ResponseStatusException");
    }

    @Test
    void meReturnsSessionForAuthenticatedUser() {
        AppUser appUser = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        AuthenticatedUser principal = new AuthenticatedUser(appUser);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        AuthSessionResponse response = sessionResponse(appUser.getId(), "owner@example.com", "Owner");

        when(authService.sessionFor(principal)).thenReturn(response);

        assertThat(controller.me(authentication)).isEqualTo(response);
    }

    @Test
    void logoutInvalidatesServerSessionAndClearsSecurityContext() {
        AppUser appUser = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        AuthenticatedUser principal = new AuthenticatedUser(appUser);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout(request, response);

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getHeader("Clear-Site-Data")).isEqualTo("\"cookies\"");
    }

    private AuthSessionResponse sessionResponse(UUID userId, String email, String displayName) {
        return new AuthSessionResponse(
                new UserSummary(userId, email, displayName),
                new WorkspaceSummary(UUID.randomUUID(), "FD Multimedia", "fd-multimedia", WorkspaceRole.OWNER));
    }
}
