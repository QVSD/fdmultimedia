package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.publishing.PublicationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicationAnalyticsService {
    private static final Logger log = LoggerFactory.getLogger(PublicationAnalyticsService.class);
    private final AuthService auth;
    private final PublicationRepository publications;
    private final PublicationAnalyticsStore store;
    private final PublicationAnalyticsProperties properties;
    private final Map<SocialPlatform, PublicationAnalyticsProvider> providers;
    private final Clock clock;

    public PublicationAnalyticsService(AuthService auth, PublicationRepository publications,
            PublicationAnalyticsStore store, PublicationAnalyticsProperties properties,
            List<PublicationAnalyticsProvider> providers, Clock clock) {
        this.auth = auth;
        this.publications = publications;
        this.store = store;
        this.properties = properties;
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                PublicationAnalyticsProvider::platform, provider -> provider));
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublicationAttribution attribution(AuthenticatedUser principal, UUID publicationId,
            PublicationAttributionService attributionService) {
        Publication publication = requirePublication(principal, publicationId);
        return attributionService.get(publication.getWorkspace().getId(), publicationId);
    }

    @Transactional(readOnly = true)
    public PublicationAnalyticsState state(AuthenticatedUser principal, UUID publicationId) {
        Publication publication = requirePublication(principal, publicationId);
        return store.state(publication.getWorkspace().getId(), publicationId);
    }

    @Transactional(readOnly = true)
    public List<PublicationAnalyticsSnapshot> history(AuthenticatedUser principal, UUID publicationId, int limit) {
        Publication publication = requirePublication(principal, publicationId);
        return store.history(publication.getWorkspace().getId(), publicationId, limit);
    }

    @Transactional(readOnly = true)
    public PublicationAnalyticsSnapshot latest(AuthenticatedUser principal, UUID publicationId) {
        return history(principal, publicationId, 1).stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PublicationAnalyticsSnapshot> list(AuthenticatedUser principal, Instant from, Instant to, int limit) {
        UUID workspaceId = auth.currentMembershipFor(principal).getWorkspace().getId();
        Instant now = Instant.now(clock);
        if (from == null || to == null || !to.isAfter(from) || to.isAfter(now.plusSeconds(60))
                || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Analytics date range must be within one year");
        }
        return store.latestForWorkspace(workspaceId, from, to, limit);
    }

    public PublicationAnalyticsSnapshot refresh(AuthenticatedUser principal, UUID publicationId) {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Analytics collection is disabled");
        }
        UUID workspaceId = auth.currentMembershipFor(principal).getWorkspace().getId();
        Publication publication = publications.findByWorkspaceAndId(
                auth.currentMembershipFor(principal).getWorkspace(), publicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Publication not found"));
        if (publication.getStatus() != com.fdmultimedia.api.publishing.PublicationStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Publication is not published");
        }
        AnalyticsClaim claim = store.claimManual(workspaceId, publicationId, Instant.now(clock));
        collect(claim);
        return store.history(workspaceId, publicationId, 1).stream().findFirst().orElse(null);
    }

    public void collectDueBatch() {
        if (!properties.isEnabled()) {
            return;
        }
        for (int i = 0; i < properties.getBatchSize(); i++) {
            var claim = store.claimDue(Instant.now(clock));
            if (claim.isEmpty()) {
                return;
            }
            try {
                collect(claim.get());
            } catch (RuntimeException ex) {
                // One provider failure must not starve other due publications.
                log.warn("Analytics collection failed for publication {}: {}", claim.get().publicationId(), ex.getClass().getSimpleName());
            }
        }
    }

    private void collect(AnalyticsClaim claim) {
        try {
            SocialPlatform platform = SocialPlatform.valueOf(claim.provider());
            PublicationAnalyticsProvider provider = providers.get(platform);
            if (provider == null) {
                throw new AnalyticsCollectionException("ANALYTICS_PROVIDER_UNAVAILABLE", "Analytics provider unavailable", false);
            }
            Publication publication = publications.findById(claim.publicationId())
                    .orElseThrow(() -> new AnalyticsCollectionException(
                            "ANALYTICS_MEDIA_NOT_FOUND", "Publication unavailable", false));
            NormalizedAnalytics metrics = provider.collect(publication, claim.ageBucket());
            if (metrics == null) {
                throw new AnalyticsCollectionException("ANALYTICS_INVALID_RESPONSE", "Invalid analytics response", true);
            }
            store.complete(claim, metrics, Instant.now(clock));
        } catch (AnalyticsCollectionException ex) {
            store.fail(claim, ex.code(), ex.getMessage(), ex.retryable() ? Duration.ofHours(1) : null,
                    Instant.now(clock));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        } catch (RuntimeException ex) {
            store.fail(claim, "ANALYTICS_INTERNAL_ERROR", "Analytics collection failed", Duration.ofHours(1),
                    Instant.now(clock));
            log.error("Unexpected analytics collection error for publication {}", claim.publicationId(), ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Analytics collection failed");
        }
    }

    private Publication requirePublication(AuthenticatedUser principal, UUID publicationId) {
        var workspace = auth.currentMembershipFor(principal).getWorkspace();
        return publications.findByWorkspaceAndId(workspace, publicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Publication not found"));
    }
}
