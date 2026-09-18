package com.fdmultimedia.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SocialOAuthStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final SocialOAuthStateRepository states = mock(SocialOAuthStateRepository.class);
    private final Map<String, SocialOAuthState> savedByHash = new HashMap<>();
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private SocialOAuthStateService service;

    private Workspace workspace;
    private AppUser user;

    @BeforeEach
    void setUp() {
        service = new SocialOAuthStateService(states, clock);
        workspace = new Workspace("FD Multimedia", "fdm");
        user = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        when(states.save(any(SocialOAuthState.class))).thenAnswer(invocation -> {
            SocialOAuthState state = invocation.getArgument(0);
            savedByHash.put(state.getStateHash(), state);
            return state;
        });
        when(states.findByStateHash(any())).thenAnswer(invocation ->
                Optional.ofNullable(savedByHash.get((String) invocation.getArgument(0))));
    }

    private String create(Duration ttl) {
        return service.create(workspace, user, "INSTAGRAM", ttl);
    }

    @Test
    void validStateCanBeConsumedExactlyOnce() {
        String rawState = create(Duration.ofMinutes(10));

        SocialOAuthState consumed = service.consume(rawState, "INSTAGRAM");

        assertThat(consumed.getWorkspace()).isEqualTo(workspace);
        assertThat(consumed.getUser()).isEqualTo(user);
        assertThatThrownBy(() -> service.consume(rawState, "INSTAGRAM"))
                .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void missingStateIsRejected() {
        assertThatThrownBy(() -> service.consume(null, "INSTAGRAM"))
                .isInstanceOf(InvalidOAuthStateException.class);
        assertThatThrownBy(() -> service.consume("", "INSTAGRAM"))
                .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void unknownStateIsRejected() {
        assertThatThrownBy(() -> service.consume("forged-token-value", "INSTAGRAM"))
                .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void expiredStateIsRejected() {
        String rawState = create(Duration.ofSeconds(1));
        advanceClockBy(Duration.ofMinutes(5));

        assertThatThrownBy(() -> service.consume(rawState, "INSTAGRAM"))
                .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void mismatchedPlatformIsRejected() {
        String rawState = create(Duration.ofMinutes(10));

        assertThatThrownBy(() -> service.consume(rawState, "TIKTOK"))
                .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void distinctStatesAreCryptographicallyUnrelated() {
        String first = create(Duration.ofMinutes(10));
        String second = create(Duration.ofMinutes(10));

        assertThat(first).isNotEqualTo(second);
    }

    private void advanceClockBy(Duration duration) {
        clock = Clock.fixed(clock.instant().plus(duration), ZoneOffset.UTC);
        service = new SocialOAuthStateService(states, clock);
    }
}
