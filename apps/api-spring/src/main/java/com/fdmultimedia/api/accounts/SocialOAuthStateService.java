package com.fdmultimedia.api.accounts;

import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and validates the cryptographically strong, short-lived, single-use
 * OAuth state token described in the Phase 10B spec. The raw token is only
 * ever embedded in the authorization URL handed to the browser; the database
 * stores only its SHA-256 hash, so a leaked database row alone cannot be used
 * to forge a valid callback.
 */
@Service
public class SocialOAuthStateService {

    private static final int TOKEN_BYTES = 32;

    private final SocialOAuthStateRepository states;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    public SocialOAuthStateService(SocialOAuthStateRepository states, Clock clock) {
        this.states = states;
        this.clock = clock;
    }

    @Transactional
    public String create(Workspace workspace, AppUser user, String platform, Duration ttl) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant now = Instant.now(clock);
        states.save(new SocialOAuthState(hash(rawToken), platform, workspace, user, now.plus(ttl), now));
        return rawToken;
    }

    /** Validates and atomically consumes a state token. Throws {@link InvalidOAuthStateException} on any failure. */
    @Transactional
    public SocialOAuthState consume(String rawToken, String platform) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidOAuthStateException("Missing state parameter");
        }
        SocialOAuthState state = states.findByStateHash(hash(rawToken))
                .orElseThrow(() -> new InvalidOAuthStateException("Unknown or already-used state"));
        Instant now = Instant.now(clock);
        if (!state.isUsable(platform, now)) {
            throw new InvalidOAuthStateException("State is expired, already used, or for a different platform");
        }
        state.consume(now);
        return state;
    }

    @Transactional
    public int deleteExpired() {
        return states.deleteExpiredBefore(Instant.now(clock));
    }

    private String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
