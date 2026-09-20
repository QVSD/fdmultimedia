package com.fdmultimedia.api.experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Item 12/13/57: the canonical per-assignment SQL shape — deterministic Publication selection, never per-Publication aggregation. */
class ExperimentAnalysisStoreTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ExperimentAnalysisStore store = new ExperimentAnalysisStore(jdbc);
    private final Instant now = Instant.parse("2026-09-20T12:00:00Z");

    @Test
    void fetchSelectsExactlyOneRowPerAssignmentViaDeterministicRowNumbering() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());

        store.fetch(UUID.randomUUID(), Window.H72, Metric.VIEWS, now);

        String sql = capturedSql();
        assertThat(sql).contains(
                "ROW_NUMBER() OVER (",
                "PARTITION BY ea.id",
                "ORDER BY p.published_at ASC NULLS LAST, p.id ASC",
                "WHERE rn = 1");
    }

    @Test
    void fetchJoinsPublicationAttributionsByExperimentAssignmentIdNotByRobotRunId() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());

        store.fetch(UUID.randomUUID(), Window.H72, Metric.VIEWS, now);

        assertThat(capturedSql()).contains("a.experiment_assignment_id = ea.id", "p.status = 'PUBLISHED'");
    }

    @Test
    void fetchUsesTheExactSameTargetWindowSnapshotSelectionAsThePhase13BStore() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());

        store.fetch(UUID.randomUUID(), Window.H72, Metric.VIEWS, now);

        assertThat(capturedSql()).contains(
                "s.publication_age_seconds BETWEEN :minimumAge AND :maximumAge",
                "ABS(s.publication_age_seconds - :targetAge), s.collected_at DESC, s.id DESC LIMIT 1");
    }

    @Test
    void fetchForLatestWindowNeverFiltersByPublicationAgeSeconds() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());

        store.fetch(UUID.randomUUID(), Window.LATEST, Metric.VIEWS, now);

        assertThat(capturedSql()).doesNotContain("s.publication_age_seconds BETWEEN");
    }

    @Test
    void fetchSelectsOnlyThePrimaryMetricColumnNeverAllSeven() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());

        store.fetch(UUID.randomUUID(), Window.H72, Metric.SAVES, now);

        String sql = capturedSql();
        assertThat(sql).contains("s.saves AS metric_value");
        assertThat(sql).doesNotContain("s.views AS metric_value");
    }

    @Test
    void countAssignmentsScopesByExperimentId() {
        UUID experimentId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.eq(Long.class)))
                .thenReturn(42L);

        long count = store.countAssignments(experimentId);

        assertThat(count).isEqualTo(42L);
    }

    @SuppressWarnings("unchecked")
    private String capturedSql() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        return sqlCaptor.getValue();
    }
}
