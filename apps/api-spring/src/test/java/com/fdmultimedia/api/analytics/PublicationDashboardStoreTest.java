package com.fdmultimedia.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.analytics.DashboardQuery.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class PublicationDashboardStoreTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PublicationDashboardStore store = new PublicationDashboardStore(jdbc);
    private final Instant now = Instant.parse("2026-09-20T12:00:00Z");

    @Test
    void latestSelectsOneSnapshotAndUsesPublishedCohort() {
        store.trend(query(Window.LATEST), Metric.VIEWS, now);
        String sql = capturedSql();
        assertThat(sql).contains("p.published_at >= :fromInstant", "p.published_at < :toExclusive",
                "ORDER BY s.collected_at DESC, s.id DESC LIMIT 1", "GROUP BY cohort_date");
        assertThat(sql).doesNotContain("s.publication_age_seconds BETWEEN");
    }

    @Test
    void targetWindowsSelectNearestSnapshotThenLaterTie() {
        store.trend(query(Window.H72), Metric.VIEWS, now);
        String sql = capturedSql();
        assertThat(sql).contains("s.publication_age_seconds BETWEEN :minimumAge AND :maximumAge",
                "ABS(s.publication_age_seconds - :targetAge), s.collected_at DESC, s.id DESC LIMIT 1");
    }

    @Test
    void breakdownUsesOnlyControlledDimensionExpressionsAndLimitsRows() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        store.breakdown(query(Window.H24), Dimension.PERSONA, now);
        assertThat(capturedSql()).contains("persona_name_snapshot", "LIMIT 101", "ORDER BY dimension_label");
    }

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        return sql.getValue();
    }

    private DashboardQuery query(Window window) {
        return new DashboardQuery(UUID.randomUUID(), LocalDate.parse("2026-08-22"),
                LocalDate.parse("2026-09-20"), window, null, null, null, null, null, null);
    }
}
