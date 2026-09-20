package com.fdmultimedia.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PublicationAnalyticsStoreTest {
    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final PublicationAnalyticsStore store = new PublicationAnalyticsStore(
            jdbc, new PublicationAnalyticsProperties());
    private final Instant now = Instant.parse("2026-09-20T00:00:00Z");
    private final AnalyticsClaim manualClaim = new AnalyticsClaim(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "INSTAGRAM", now.minusSeconds(3600), 0, "MANUAL:1",
            UUID.randomUUID(), true);

    @Test
    void terminalManualFailurePausesScheduledCollection() {
        store.fail(manualClaim, "ANALYTICS_PERMISSION_DENIED", "Permission unavailable", null, now);
        assertThat(jdbc.sql).contains("next_collection_at = CASE WHEN ? THEN next_collection_at ELSE ? END");
        assertThat(jdbc.sql).contains("claim_expires_at > ?");
        assertThat(jdbc.args[2]).isEqualTo(false);
        assertThat(jdbc.args[3]).isNull();
        assertThat(jdbc.args[6]).isNotNull();
    }

    @Test
    void transientManualFailurePreservesScheduledDueTime() {
        store.fail(manualClaim, "ANALYTICS_RATE_LIMITED", "Rate limited", Duration.ofHours(1), now);
        assertThat(jdbc.args[2]).isEqualTo(true);
        assertThat(jdbc.args[3]).isNotNull();
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private String sql;
        private Object[] args;

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return 1;
        }
    }
}
