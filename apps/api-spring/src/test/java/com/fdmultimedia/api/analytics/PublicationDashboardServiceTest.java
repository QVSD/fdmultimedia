package com.fdmultimedia.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PublicationDashboardServiceTest {
    private final AuthService auth = mock(AuthService.class);
    private final PublicationDashboardStore store = mock(PublicationDashboardStore.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);
    private final PublicationDashboardService service = new PublicationDashboardService(auth, store, clock);
    private final AuthenticatedUser user = mock(AuthenticatedUser.class);

    @BeforeEach
    void membership() {
        Workspace workspace = mock(Workspace.class);
        WorkspaceMembership membership = mock(WorkspaceMembership.class);
        when(auth.currentMembershipFor(user)).thenReturn(membership);
        when(membership.getWorkspace()).thenReturn(workspace);
    }

    @Test
    void defaultsToThirtyDayUtcCohortAndLatest() {
        UUID workspaceId = UUID.randomUUID();
        when(auth.currentMembershipFor(user).getWorkspace().getId()).thenReturn(workspaceId);
        var query = service.query(user, request(null, null, null));
        assertThat(query.workspaceId()).isEqualTo(workspaceId);
        assertThat(query.from()).hasToString("2026-08-22");
        assertThat(query.to()).hasToString("2026-09-20");
        assertThat(query.window()).isEqualTo(Window.LATEST);
    }

    @Test
    void validatesRangeAndControlledEnums() {
        assertThatThrownBy(() -> service.query(user, request("2025-01-01", "2026-09-20", "H24")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.query(user, request("2026-09-21", "2026-09-21", "LATEST")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.query(user, request(null, null, "DROP TABLE publications")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.query(user, request("invalid", null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void ageWindowsHaveExplicitNonOverlappingTolerance() {
        assertThat(Window.H24.minimumSeconds()).isEqualTo(18 * 3600);
        assertThat(Window.H24.maximumSeconds()).isEqualTo(36 * 3600);
        assertThat(Window.H72.minimumSeconds()).isEqualTo(60 * 3600);
        assertThat(Window.H72.maximumSeconds()).isEqualTo(96 * 3600);
        assertThat(Window.D7.minimumSeconds()).isEqualTo(6 * 86400);
        assertThat(Window.D7.maximumSeconds()).isEqualTo(8 * 86400);
    }

    private static PublicationDashboardService.Request request(String from, String to, String window) {
        return new PublicationDashboardService.Request(from, to, window, null, null, null, null, null, null);
    }
}
