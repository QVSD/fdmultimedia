package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.DashboardModels.BreakdownRow;
import com.fdmultimedia.api.analytics.DashboardModels.Coverage;
import com.fdmultimedia.api.analytics.DashboardModels.MetricAggregate;
import com.fdmultimedia.api.analytics.DashboardModels.Summary;
import com.fdmultimedia.api.analytics.DashboardQuery.Dimension;
import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.analytics.InsightModels.ComparisonResult;
import com.fdmultimedia.api.analytics.InsightModels.Direction;
import com.fdmultimedia.api.analytics.InsightModels.InsightsResponse;
import com.fdmultimedia.api.analytics.InsightModels.Notice;
import com.fdmultimedia.api.analytics.InsightModels.Recommendation;
import com.fdmultimedia.api.analytics.InsightModels.RecommendationType;
import com.fdmultimedia.api.analytics.InsightModels.ResultType;
import com.fdmultimedia.api.analytics.InsightModels.SegmentEvidence;
import com.fdmultimedia.api.analytics.InsightModels.Statistic;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 13C: deterministic, descriptive observations over the immutable
 * Phase 13A snapshots/attribution and the Phase 13B dashboard's own cohort
 * semantics — never a second snapshot-selection algorithm, never an AI call,
 * never a domain mutation. See {@code docs/ARCHITECTURE.md} for the full
 * decision-tree rationale (why TOO_YOUNG outranks METRIC_UNAVAILABLE which
 * outranks INSUFFICIENT_SAMPLE which outranks LOW_COVERAGE).
 *
 * <p>Same dataset + same filters + same {@link #ENGINE_VERSION} always
 * produces the same {@link ComparisonResult}, including its {@code id} —
 * this class never calls {@code UUID.randomUUID()} or reads wall-clock time
 * into anything other than the (already deterministic-per-request) snapshot
 * selection {@link PublicationDashboardStore} performs.
 */
@Service
public class PerformanceInsightService {
    public static final String ENGINE_VERSION = "PERFORMANCE_INSIGHTS_V1";

    private static final String DISCLAIMER =
            "These comparisons describe observed associations in the selected publications and do not establish causation.";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final List<Dimension> AUTOMATIC_DIMENSIONS = List.of(Dimension.ORIGIN, Dimension.AI_USAGE);
    /**
     * Deliberately smaller than Phase 13B's own recommended default metric
     * set (VIEWS/SHARES/TOTAL_INTERACTIONS) so that 2 automatic dimensions x
     * 2 automatic metrics = 4 stays comfortably under the "at most 5
     * directional insights per request" bound without needing an arbitrary
     * truncation rule at the boundary. Explicit compare accepts any of the 7
     * metrics.
     */
    private static final List<Metric> AUTOMATIC_METRICS = List.of(Metric.VIEWS, Metric.TOTAL_INTERACTIONS);

    private final PublicationDashboardService dashboardService;
    private final PublicationDashboardStore store;
    private final PerformanceInsightProperties properties;
    private final Clock clock;

    public PerformanceInsightService(PublicationDashboardService dashboardService, PublicationDashboardStore store,
            PerformanceInsightProperties properties, Clock clock) {
        this.dashboardService = dashboardService;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public InsightsResponse insights(AuthenticatedUser user, InsightsRequest request) {
        DashboardQuery query = dashboardService.query(user, toDashboardRequest(request));
        Instant now = Instant.now(clock);
        Summary summary = store.summary(query, now);
        List<Notice> notices = maturityNotices(summary, query.window());
        List<ComparisonResult> observations = new ArrayList<>();
        for (Dimension dimension : AUTOMATIC_DIMENSIONS) {
            String[] pair = canonicalPair(dimension);
            for (Metric metric : AUTOMATIC_METRICS) {
                observations.add(buildComparison(query, dimension, pair[0], pair[1], metric, Statistic.MEDIAN, now));
            }
        }
        return new InsightsResponse(ENGINE_VERSION, fingerprint(query, null, null, null), DISCLAIMER, notices, observations);
    }

    public ComparisonResult compare(AuthenticatedUser user, CompareRequest request) {
        DashboardQuery query = dashboardService.query(user, toDashboardRequest(request));
        Dimension dimension = parseEnum(Dimension.class, request.dimension(), "dimension");
        if (dimension == Dimension.EXPERIMENT_VARIANT) {
            // Item 101: experiment variant comparisons are deliberately kept out of the
            // general insights engine in this phase — see ExperimentOutcomeService instead.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Experiment variant comparisons are not available through this endpoint");
        }
        Metric metric = parseEnum(Metric.class, request.metric(), "metric");
        Statistic statistic = request.statistic() == null || request.statistic().isBlank()
                ? Statistic.MEDIAN : parseEnum(Statistic.class, request.statistic(), "statistic");
        String left = normalizeSegmentId(dimension, request.leftSegmentId());
        String right = normalizeSegmentId(dimension, request.rightSegmentId());
        if (left.equals(right)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot compare a segment to itself");
        }
        return buildComparison(query, dimension, left, right, metric, statistic, Instant.now(clock));
    }

    // ---- shared comparison engine ----

    private ComparisonResult buildComparison(DashboardQuery query, Dimension dimension, String leftKey, String rightKey,
            Metric metric, Statistic statistic, Instant now) {
        List<BreakdownRow> rows = store.segments(query, dimension, List.of(leftKey, rightKey), now);
        Map<String, BreakdownRow> byKey = rows.stream().collect(Collectors.toMap(BreakdownRow::key, row -> row));
        SegmentEvidence left = evidence(leftKey, byKey.get(leftKey), dimension, metric);
        SegmentEvidence right = evidence(rightKey, byKey.get(rightKey), dimension, metric);
        ResultType type = classify(left, right);
        String id = deterministicId(query, dimension, metric, statistic, leftKey, rightKey, type);
        List<String> limitations = limitations(query, dimension, left, right);

        if (type != ResultType.DIRECTIONAL_COMPARISON) {
            return new ComparisonResult(id, type, ENGINE_VERSION, dimension, metric, statistic, query.window(),
                    left, right, null, null, null, false,
                    nonDirectionalMessage(type, metric, query.window(), left, right),
                    limitations, recommendationsFor(type, false));
        }

        BigDecimal leftValue = statistic == Statistic.MEDIAN ? left.medianValue() : left.averageValue();
        BigDecimal rightValue = statistic == Statistic.MEDIAN ? right.medianValue() : right.averageValue();
        BigDecimal absoluteDifference = leftValue.subtract(rightValue);
        BigDecimal relativeDifferencePercent = rightValue.signum() == 0 ? null
                : absoluteDifference.divide(rightValue.abs(), 6, RoundingMode.HALF_UP).multiply(ONE_HUNDRED);
        // A zero denominator makes a relative PERCENT undefined, but the comparison is still
        // materially different whenever the other side is non-zero — this only governs the
        // reported relativeDifferencePercent field, not the direction/materiality decision.
        boolean material = rightValue.signum() == 0
                ? leftValue.signum() != 0
                : relativeDifferencePercent.abs().compareTo(properties.getMaterialDifferencePercent()) >= 0;
        Direction direction = !material ? Direction.SIMILAR_OBSERVED
                : (absoluteDifference.signum() > 0 ? Direction.HIGHER_OBSERVED : Direction.LOWER_OBSERVED);
        String message = directionalMessage(metric, statistic, query.window(), left, right, leftValue, rightValue, direction);
        return new ComparisonResult(id, type, ENGINE_VERSION, dimension, metric, statistic, query.window(),
                left, right, absoluteDifference, relativeDifferencePercent, direction, material, message,
                limitations, recommendationsFor(type, material));
    }

    private SegmentEvidence evidence(String key, BreakdownRow row, Dimension dimension, Metric metric) {
        if (row == null) {
            return new SegmentEvidence(key, fallbackLabel(dimension, key), 0, 0, 0, 0, null, null, null);
        }
        Coverage coverage = row.coverage();
        MetricAggregate aggregate = row.metrics().get(metric);
        BigDecimal ratio = coverage.eligibleByAgeCount() > 0
                ? BigDecimal.valueOf(aggregate.sampleCount())
                        .divide(BigDecimal.valueOf(coverage.eligibleByAgeCount()), 4, RoundingMode.HALF_UP)
                : null;
        return new SegmentEvidence(key, row.label(), coverage.publicationCount(), coverage.eligibleByAgeCount(),
                coverage.analyticsPublicationCount(), aggregate.sampleCount(), ratio, aggregate.median(), aggregate.average());
    }

    private static String fallbackLabel(Dimension dimension, String key) {
        return switch (dimension) {
            case ROBOT -> key.equals("NONE") ? "Manual / No Robot" : key;
            case PERSONA -> key.equals("NONE") ? "No applied Persona" : key;
            case CONTENT_SOURCE -> key.equals("NONE") ? "No ContentSource" : key;
            case PROVIDER, ORIGIN, AI_USAGE, EXPERIMENT_VARIANT -> key;
        };
    }

    private ResultType classify(SegmentEvidence left, SegmentEvidence right) {
        if (tooYoung(left) || tooYoung(right)) {
            return ResultType.TOO_YOUNG;
        }
        if (metricUnavailable(left) || metricUnavailable(right)) {
            return ResultType.METRIC_UNAVAILABLE;
        }
        if (left.sampleCount() < properties.getMinSampleSize() || right.sampleCount() < properties.getMinSampleSize()) {
            return ResultType.INSUFFICIENT_SAMPLE;
        }
        if (belowCoverage(left) || belowCoverage(right)) {
            return ResultType.LOW_COVERAGE;
        }
        return ResultType.DIRECTIONAL_COMPARISON;
    }

    private static boolean tooYoung(SegmentEvidence evidence) {
        return evidence.publicationCount() > 0 && evidence.eligibleByAgeCount() == 0;
    }

    private boolean metricUnavailable(SegmentEvidence evidence) {
        return evidence.analyticsPublicationCount() >= properties.getMinSampleSize() && evidence.sampleCount() == 0;
    }

    private boolean belowCoverage(SegmentEvidence evidence) {
        return evidence.coverage() == null || evidence.coverage().compareTo(properties.getMinCoverage()) < 0;
    }

    // ---- automatic candidate policy ----

    private static String[] canonicalPair(Dimension dimension) {
        return switch (dimension) {
            case ORIGIN -> new String[] {"MANUAL", "ROBOT"};
            case AI_USAGE -> new String[] {"AI_APPLIED", "NO_APPLIED_AI"};
            default -> throw new IllegalArgumentException("No automatic segment pair defined for " + dimension);
        };
    }

    private List<Notice> maturityNotices(Summary summary, Window window) {
        List<Notice> notices = new ArrayList<>();
        Coverage coverage = summary.coverage();
        if (coverage.publicationCount() == 0) {
            notices.add(new Notice("EMPTY", "No published content matches the selected filters."));
            return notices;
        }
        if (coverage.tooYoungCount() > 0) {
            notices.add(new Notice("MATURITY", "%d recent publication(s) have not yet reached %s."
                    .formatted(coverage.tooYoungCount(), windowPhrase(window))));
        }
        if (coverage.missingSnapshotCount() > 0) {
            notices.add(new Notice("COVERAGE", "%d eligible publication(s) are missing a snapshot at %s."
                    .formatted(coverage.missingSnapshotCount(), windowPhrase(window))));
        }
        return notices;
    }

    // ---- messages ----

    private String directionalMessage(Metric metric, Statistic statistic, Window window, SegmentEvidence left,
            SegmentEvidence right, BigDecimal leftValue, BigDecimal rightValue, Direction direction) {
        String stat = statistic == Statistic.MEDIAN ? "median" : "average";
        String comparison = switch (direction) {
            case HIGHER_OBSERVED -> "had a higher observed";
            case LOWER_OBSERVED -> "had a lower observed";
            case SIMILAR_OBSERVED -> "had a similar observed";
        };
        return "At %s, %s %s %s %s than %s in this sample (%s vs %s; n=%d vs n=%d)."
                .formatted(windowPhrase(window), left.label(), comparison, stat, metricLabel(metric), right.label(),
                        plain(leftValue), plain(rightValue), left.sampleCount(), right.sampleCount());
    }

    private String nonDirectionalMessage(ResultType type, Metric metric, Window window, SegmentEvidence left, SegmentEvidence right) {
        return switch (type) {
            case TOO_YOUNG -> "%s and/or %s have recent publications that have not yet reached %s; no comparison was generated."
                    .formatted(left.label(), right.label(), windowPhrase(window));
            case METRIC_UNAVAILABLE -> "The analytics provider does not report %s for %s and/or %s in this sample."
                    .formatted(metricLabel(metric), left.label(), right.label());
            case INSUFFICIENT_SAMPLE -> "Not enough %s observations are available to compare %s and %s at %s (n=%d vs n=%d; %d required per segment)."
                    .formatted(metricLabel(metric), left.label(), right.label(), windowPhrase(window),
                            left.sampleCount(), right.sampleCount(), properties.getMinSampleSize());
            case LOW_COVERAGE -> "%s and/or %s have analytics coverage below the configured threshold at %s, so no comparative observation was generated."
                    .formatted(left.label(), right.label(), windowPhrase(window));
            case DIRECTIONAL_COMPARISON -> throw new IllegalStateException("Directional comparisons use directionalMessage");
        };
    }

    private static String windowPhrase(Window window) {
        return switch (window) {
            case LATEST -> "the latest observation";
            case H24 -> "the 24-hour observation window";
            case H72 -> "the 72-hour observation window";
            case D7 -> "the 7-day observation window";
        };
    }

    private static String metricLabel(Metric metric) {
        return switch (metric) {
            case VIEWS -> "views";
            case REACH -> "reach";
            case LIKES -> "likes";
            case COMMENTS -> "comments";
            case SHARES -> "shares";
            case SAVES -> "saves";
            case TOTAL_INTERACTIONS -> "total interactions";
        };
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    // ---- recommendations (item 32: every recommendation is tied to a specific evidence condition) ----

    private List<Recommendation> recommendationsFor(ResultType type, boolean materialDifference) {
        return switch (type) {
            case TOO_YOUNG -> List.of(new Recommendation(RecommendationType.WAIT_FOR_OBSERVATION_WINDOW,
                    "Wait for more publications to reach this observation window before comparing."));
            case INSUFFICIENT_SAMPLE -> List.of(new Recommendation(RecommendationType.COLLECT_MORE_DATA,
                    "Collect more observations before comparing these segments."));
            case LOW_COVERAGE -> List.of(new Recommendation(RecommendationType.CHECK_ANALYTICS_COVERAGE,
                    "Check analytics collection coverage for this segment before drawing conclusions."));
            case METRIC_UNAVAILABLE -> List.of(new Recommendation(RecommendationType.CHECK_ANALYTICS_COVERAGE,
                    "Check whether the analytics provider supports this metric for this segment."));
            case DIRECTIONAL_COMPARISON -> materialDifference
                    ? List.of(new Recommendation(RecommendationType.REVIEW_CONTENT_DIFFERENCES,
                            "Review the publications in both cohorts to identify content differences not represented by these analytics dimensions."))
                    : List.of();
        };
    }

    // ---- limitations ----

    private List<String> limitations(DashboardQuery query, Dimension dimension, SegmentEvidence left, SegmentEvidence right) {
        List<String> limitations = new ArrayList<>();
        limitations.add(DISCLAIMER);
        boolean involvesTest = query.provider() == null || "TEST".equals(query.provider())
                || (dimension == Dimension.PROVIDER && ("TEST".equals(left.segmentId()) || "TEST".equals(right.segmentId())));
        if (involvesTest) {
            limitations.add("TEST analytics are deterministic development data, not real audience behavior.");
        }
        if (dimension == Dimension.PROVIDER) {
            limitations.add("Normalized metric semantics may differ across providers; this compares the same field name, not necessarily equivalent audience behavior.");
        }
        return limitations;
    }

    // ---- request parsing/validation ----

    private static PublicationDashboardService.Request toDashboardRequest(InsightsRequest r) {
        return new PublicationDashboardService.Request(r.dateFrom(), r.dateTo(), r.window(), r.provider(),
                r.robotId(), r.personaId(), r.contentSourceId(), r.origin(), r.aiUsage());
    }

    private static PublicationDashboardService.Request toDashboardRequest(CompareRequest r) {
        return new PublicationDashboardService.Request(r.dateFrom(), r.dateTo(), r.window(), r.provider(),
                r.robotId(), r.personaId(), r.contentSourceId(), r.origin(), r.aiUsage());
    }

    private static String normalizeSegmentId(Dimension dimension, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Segment ID is required");
        }
        String value = raw.trim();
        switch (dimension) {
            case ROBOT, PERSONA, CONTENT_SOURCE -> {
                if (!"NONE".equals(value)) {
                    try {
                        UUID.fromString(value);
                    } catch (IllegalArgumentException ex) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid segment ID");
                    }
                }
            }
            case PROVIDER -> {
                if (!value.equals("TEST") && !value.equals("INSTAGRAM")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider segment");
                }
            }
            case ORIGIN -> {
                if (!value.equals("MANUAL") && !value.equals("ROBOT")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid origin segment");
                }
            }
            case AI_USAGE -> {
                if (!value.equals("AI_APPLIED") && !value.equals("NO_APPLIED_AI")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid AI usage segment");
                }
            }
            // Unreachable in practice: compare() rejects EXPERIMENT_VARIANT before this is ever called (item 101).
            case EXPERIMENT_VARIANT -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported dimension");
        }
        return value;
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing " + field);
        }
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field);
        }
    }

    // ---- deterministic IDs / fingerprint (item 49/50 — never a random UUID) ----

    private String deterministicId(DashboardQuery query, Dimension dimension, Metric metric, Statistic statistic,
            String leftKey, String rightKey, ResultType type) {
        String raw = ENGINE_VERSION + "|" + fingerprint(query, dimension, metric, statistic) + "|" + leftKey + "|" + rightKey + "|" + type;
        return sha256Hex(raw);
    }

    private static String fingerprint(DashboardQuery query, Dimension dimension, Metric metric, Statistic statistic) {
        return String.join("|",
                Objects.toString(query.from(), "-"), Objects.toString(query.to(), "-"), query.window().name(),
                Objects.toString(query.provider(), "-"), Objects.toString(query.robotId(), "-"),
                Objects.toString(query.personaId(), "-"), Objects.toString(query.contentSourceId(), "-"),
                Objects.toString(query.origin(), "-"), Objects.toString(query.aiUsage(), "-"),
                dimension == null ? "-" : dimension.name(), metric == null ? "-" : metric.name(),
                statistic == null ? "-" : statistic.name());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record InsightsRequest(String dateFrom, String dateTo, String window, String provider,
            String robotId, String personaId, String contentSourceId, String origin, String aiUsage) {}

    public record CompareRequest(String dateFrom, String dateTo, String window, String provider,
            String robotId, String personaId, String contentSourceId, String origin, String aiUsage,
            String dimension, String leftSegmentId, String rightSegmentId, String metric, String statistic) {}
}
