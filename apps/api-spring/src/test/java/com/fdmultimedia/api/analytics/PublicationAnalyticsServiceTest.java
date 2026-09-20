package com.fdmultimedia.api.analytics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.publishing.PublicationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicationAnalyticsServiceTest {
    private final Instant now = Instant.parse("2026-09-20T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final PublicationRepository publications = mock(PublicationRepository.class);
    private final PublicationAnalyticsStore store = mock(PublicationAnalyticsStore.class);
    private final PublicationAnalyticsProperties properties = new PublicationAnalyticsProperties();
    private final PublicationAnalyticsProvider provider = mock(PublicationAnalyticsProvider.class);

    private PublicationAnalyticsService service() {
        when(provider.platform()).thenReturn(SocialPlatform.TEST);
        return new PublicationAnalyticsService(
                mock(AuthService.class), publications, store, properties, List.of(provider), clock);
    }

    @Test
    void disabledSchedulerDoesNotClaim() {
        properties.setEnabled(false);
        service().collectDueBatch();
        verify(store, never()).claimDue(any());
    }

    @Test
    void successfulClaimPersistsProviderResult() {
        AnalyticsClaim claim = claim();
        Publication publication = mock(Publication.class);
        NormalizedAnalytics metrics = new NormalizedAnalytics(0L, null, null, null, null,
                null, null, null, null, null, "TEST_ANALYTICS_V1");
        when(store.claimDue(now)).thenReturn(Optional.of(claim), Optional.empty());
        when(publications.findById(claim.publicationId())).thenReturn(Optional.of(publication));
        when(provider.collect(publication, 0)).thenReturn(metrics);
        service().collectDueBatch();
        verify(store).complete(claim, metrics, now);
    }

    @Test
    void unavailableProviderRecordsSafeFailureAndContinuesBatch() {
        AnalyticsClaim claim = claim();
        when(store.claimDue(now)).thenReturn(Optional.of(claim), Optional.empty());
        new PublicationAnalyticsService(mock(AuthService.class), publications, store,
                properties, List.of(), clock).collectDueBatch();
        verify(store).fail(eq(claim), eq("ANALYTICS_PROVIDER_UNAVAILABLE"),
                eq("Analytics provider unavailable"), eq(null), eq(now));
        verify(store, never()).complete(any(), any(), any());
    }

    @Test
    void transientProviderFailureUsesBoundedBackoff() {
        AnalyticsClaim claim = claim();
        Publication publication = mock(Publication.class);
        when(store.claimDue(now)).thenReturn(Optional.of(claim), Optional.empty());
        when(publications.findById(claim.publicationId())).thenReturn(Optional.of(publication));
        when(provider.collect(publication, 0)).thenThrow(new AnalyticsCollectionException(
                "ANALYTICS_RATE_LIMITED", "Analytics rate limited", true));
        service().collectDueBatch();
        verify(store).fail(claim, "ANALYTICS_RATE_LIMITED", "Analytics rate limited",
                Duration.ofHours(1), now);
    }

    private AnalyticsClaim claim() {
        return new AnalyticsClaim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "TEST", now.minusSeconds(3600), 0, "AGE:0", UUID.randomUUID(), false);
    }
}
