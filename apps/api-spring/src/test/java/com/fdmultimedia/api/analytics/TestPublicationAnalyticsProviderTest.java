package com.fdmultimedia.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.publishing.Publication;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TestPublicationAnalyticsProviderTest {
    private final TestPublicationAnalyticsProvider provider = new TestPublicationAnalyticsProvider();

    @Test
    void samePublicationAndBucketAreDeterministicAndLaterBucketsGrow() {
        Publication publication = mock(Publication.class);
        when(publication.getId()).thenReturn(UUID.fromString("830b1129-61bb-4796-8f1f-03f75e478c21"));
        NormalizedAnalytics first = provider.collect(publication, 0);
        NormalizedAnalytics again = provider.collect(publication, 0);
        NormalizedAnalytics later = provider.collect(publication, 5);
        assertThat(again).isEqualTo(first);
        assertThat(later.views()).isGreaterThan(first.views());
        assertThat(later.likes()).isGreaterThan(first.likes());
        assertThat(later.comments()).isGreaterThan(first.comments());
        assertThat(later.shares()).isGreaterThan(first.shares());
        assertThat(first.providerMetricVersion()).isEqualTo("TEST_ANALYTICS_V1");
    }

    @Test
    void zeroAndMissingAreDifferentAndNegativeMetricsAreRejected() {
        NormalizedAnalytics observedZero = new NormalizedAnalytics(0L, null, null, null, null,
                null, null, null, null, null, "TEST_ANALYTICS_V1");
        assertThat(observedZero.views()).isZero();
        assertThat(observedZero.reach()).isNull();
        assertThatThrownBy(() -> new NormalizedAnalytics(-1L, null, null, null, null,
                null, null, null, null, null, "TEST_ANALYTICS_V1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
