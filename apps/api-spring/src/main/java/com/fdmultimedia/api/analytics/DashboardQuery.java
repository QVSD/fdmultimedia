package com.fdmultimedia.api.analytics;

import java.time.LocalDate;
import java.util.UUID;

public record DashboardQuery(UUID workspaceId, LocalDate from, LocalDate to, Window window,
        String provider, UUID robotId, UUID personaId, UUID contentSourceId,
        Origin origin, AiUsage aiUsage, UUID experimentId) {

    /** Backward-compatible overload (no experiment filter) — every pre-Phase-14A call site keeps working unchanged. */
    public DashboardQuery(UUID workspaceId, LocalDate from, LocalDate to, Window window,
            String provider, UUID robotId, UUID personaId, UUID contentSourceId,
            Origin origin, AiUsage aiUsage) {
        this(workspaceId, from, to, window, provider, robotId, personaId, contentSourceId, origin, aiUsage, null);
    }

    public enum Window {
        LATEST(0, 0, Long.MAX_VALUE),
        H24(86_400, 64_800, 129_600),
        H72(259_200, 216_000, 345_600),
        D7(604_800, 518_400, 691_200);

        private final long targetSeconds;
        private final long minimumSeconds;
        private final long maximumSeconds;

        Window(long targetSeconds, long minimumSeconds, long maximumSeconds) {
            this.targetSeconds = targetSeconds;
            this.minimumSeconds = minimumSeconds;
            this.maximumSeconds = maximumSeconds;
        }

        public long targetSeconds() { return targetSeconds; }
        public long minimumSeconds() { return minimumSeconds; }
        public long maximumSeconds() { return maximumSeconds; }
    }

    public enum Origin { MANUAL, ROBOT }
    public enum AiUsage { AI_APPLIED, NO_APPLIED_AI }
    /**
     * EXPERIMENT_VARIANT (Phase 14A): keyed by {@code experiment_variant_id},
     * used exclusively by {@code ExperimentOutcomeService} via {@link
     * com.fdmultimedia.api.analytics.PublicationDashboardStore#segments} —
     * never surfaced through {@code PerformanceInsightService}'s automatic or
     * explicit compare endpoints (see item 101: experiment variant
     * comparisons stay out of the general insights engine in this phase).
     */
    public enum Dimension { ROBOT, PERSONA, CONTENT_SOURCE, PROVIDER, ORIGIN, AI_USAGE, EXPERIMENT_VARIANT }

    public enum Metric {
        VIEWS("views"), REACH("reach"), LIKES("likes"), COMMENTS("comments"),
        SHARES("shares"), SAVES("saves"), TOTAL_INTERACTIONS("total_interactions");

        private final String column;

        Metric(String column) { this.column = column; }
        public String column() { return column; }
    }
}
