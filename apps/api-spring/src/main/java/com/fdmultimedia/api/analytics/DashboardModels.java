package com.fdmultimedia.api.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DashboardModels {
    private DashboardModels() {}

    public record Filters(LocalDate from, LocalDate to, DashboardQuery.Window window,
            String provider, UUID robotId, UUID personaId, UUID contentSourceId,
            DashboardQuery.Origin origin, DashboardQuery.AiUsage aiUsage) {}

    public record Coverage(long publicationCount, long analyticsPublicationCount,
            long eligibleByAgeCount, long tooYoungCount, long missingSnapshotCount) {}

    public record MetricAggregate(BigDecimal total, BigDecimal average,
            BigDecimal median, long sampleCount) {}

    public record Summary(Filters filters, Coverage coverage,
            Map<DashboardQuery.Metric, MetricAggregate> metrics) {}

    public record TrendPoint(LocalDate date, Coverage coverage, MetricAggregate metric) {}

    public record Trend(Filters filters, DashboardQuery.Metric metric,
            List<TrendPoint> points) {}

    public record BreakdownRow(String key, String label, Coverage coverage,
            Map<DashboardQuery.Metric, MetricAggregate> metrics) {}

    public record Breakdown(Filters filters, DashboardQuery.Dimension dimension,
            List<BreakdownRow> rows, boolean truncated) {}

    public record Option(String id, String label) {}

    public record FilterOptions(List<String> providers, List<Option> robots,
            List<Option> personas, List<Option> contentSources,
            boolean truncated) {}

    public static Filters filters(DashboardQuery query) {
        return new Filters(query.from(), query.to(), query.window(), query.provider(),
                query.robotId(), query.personaId(), query.contentSourceId(),
                query.origin(), query.aiUsage());
    }
}
