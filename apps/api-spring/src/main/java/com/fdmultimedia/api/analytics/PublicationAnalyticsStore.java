package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.publishing.Publication;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class PublicationAnalyticsStore {
    private final JdbcTemplate jdbc;
    private final PublicationAnalyticsProperties properties;

    public PublicationAnalyticsStore(JdbcTemplate jdbc, PublicationAnalyticsProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public void schedulePublished(Publication publication) {
        Instant due = publication.getPublishedAt().plus(Duration.ofMinutes(properties.getCadenceMinutes().getFirst()));
        jdbc.update("""
                INSERT INTO publication_analytics_states(publication_id, workspace_id, next_collection_at)
                VALUES (?, ?, ?) ON CONFLICT (publication_id) DO NOTHING
                """, publication.getId(), publication.getWorkspace().getId(), ts(due));
    }

    @Transactional
    public Optional<AnalyticsClaim> claimDue(Instant now) {
        UUID token = UUID.randomUUID();
        List<AnalyticsClaim> rows = jdbc.query("""
                WITH due AS (
                    SELECT s.publication_id FROM publication_analytics_states s
                    JOIN publications p ON p.id = s.publication_id
                    WHERE p.status = 'PUBLISHED' AND s.completed_at IS NULL
                      AND s.next_collection_at <= ?
                      AND (s.claim_expires_at IS NULL OR s.claim_expires_at <= ?)
                    ORDER BY s.next_collection_at, s.publication_id
                    LIMIT 1 FOR UPDATE OF s SKIP LOCKED
                ), claimed AS (
                    UPDATE publication_analytics_states s
                    SET claim_token = ?, claim_expires_at = ?, last_attempt_at = ?
                    FROM due WHERE s.publication_id = due.publication_id
                    RETURNING s.publication_id, s.workspace_id, s.next_bucket, s.claim_token
                )
                SELECT c.*, p.social_account_id, a.platform, p.published_at
                FROM claimed c JOIN publications p ON p.id = c.publication_id
                JOIN social_accounts a ON a.id = p.social_account_id
                """, (rs, row) -> mapClaim(rs, false), ts(now), ts(now), token,
                ts(now.plus(properties.getClaimLease())), ts(now));
        return rows.stream().findFirst();
    }

    @Transactional
    public AnalyticsClaim claimManual(UUID workspaceId, UUID publicationId, Instant now) {
        List<AnalyticsClaim> rows = jdbc.query("""
                SELECT s.publication_id, s.workspace_id, s.next_bucket, p.social_account_id,
                       a.platform, p.published_at, s.claim_token, s.claim_expires_at, s.last_attempt_at
                FROM publication_analytics_states s JOIN publications p ON p.id = s.publication_id
                JOIN social_accounts a ON a.id = p.social_account_id
                WHERE s.workspace_id = ? AND s.publication_id = ? AND p.status = 'PUBLISHED'
                FOR UPDATE OF s
                """, (rs, row) -> mapClaim(rs, true), workspaceId, publicationId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Publication has no published analytics state");
        }
        Instant lastAttempt = jdbc.queryForObject("""
                SELECT last_attempt_at FROM publication_analytics_states WHERE publication_id = ?
                """, (rs, row) -> instant(rs, "last_attempt_at"), publicationId);
        Instant lease = jdbc.queryForObject("""
                SELECT claim_expires_at FROM publication_analytics_states WHERE publication_id = ?
                """, (rs, row) -> instant(rs, "claim_expires_at"), publicationId);
        if (lease != null && lease.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Analytics collection already in progress");
        }
        if (lastAttempt != null && lastAttempt.plus(properties.getManualRefreshMinimum()).isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Analytics refresh is too soon");
        }
        UUID token = UUID.randomUUID();
        jdbc.update("""
                UPDATE publication_analytics_states SET claim_token = ?, claim_expires_at = ?, last_attempt_at = ?
                WHERE publication_id = ?
                """, token, ts(now.plus(properties.getClaimLease())), ts(now), publicationId);
        AnalyticsClaim row = rows.getFirst();
        String bucket = "MANUAL:" + now.getEpochSecond() / properties.getManualRefreshMinimum().toSeconds();
        long ageMinutes = Math.max(0, Duration.between(row.publishedAt(), now).toMinutes());
        int observedBucket = 0;
        for (int i = 1; i < properties.getCadenceMinutes().size(); i++) {
            if (ageMinutes < properties.getCadenceMinutes().get(i)) break;
            observedBucket = i;
        }
        return new AnalyticsClaim(row.publicationId(), row.workspaceId(), row.socialAccountId(),
                row.provider(), row.publishedAt(), observedBucket, bucket, token, true);
    }

    @Transactional
    public void complete(AnalyticsClaim claim, NormalizedAnalytics metrics, Instant now) {
        Integer currentBucket = jdbc.query("""
                SELECT next_bucket FROM publication_analytics_states
                WHERE publication_id = ? AND claim_token = ? AND claim_expires_at > ? FOR UPDATE
                """, (rs, row) -> rs.getInt(1), claim.publicationId(), claim.claimToken(), ts(now))
                .stream().findFirst().orElse(null);
        if (currentBucket == null) {
            return; // Late provider response after lease recovery; never write a stale snapshot.
        }
        jdbc.update("""
                INSERT INTO publication_analytics_snapshots (
                    id, workspace_id, publication_id, social_account_id, provider, bucket_key,
                    collected_at, provider_observed_at, publication_age_seconds,
                    views, reach, likes, comments, shares, saves, total_interactions,
                    watch_time_ms, average_watch_time_ms, provider_metric_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (publication_id, bucket_key) DO NOTHING
                """, UUID.randomUUID(), claim.workspaceId(), claim.publicationId(), claim.socialAccountId(),
                claim.provider(), claim.bucketKey(), ts(now), ts(metrics.providerObservedAt()),
                Math.max(0, Duration.between(claim.publishedAt(), now).toSeconds()),
                metrics.views(), metrics.reach(), metrics.likes(), metrics.comments(), metrics.shares(),
                metrics.saves(), metrics.totalInteractions(), metrics.watchTimeMs(),
                metrics.averageWatchTimeMs(), metrics.providerMetricVersion(), ts(now));
        Integer nextBucket = claim.manual() ? currentBucket : currentBucket + 1;
        Instant next = nextBucket >= properties.getCadenceMinutes().size() ? null
                : claim.publishedAt().plus(Duration.ofMinutes(properties.getCadenceMinutes().get(nextBucket)));
        jdbc.update("""
                UPDATE publication_analytics_states SET next_bucket = ?, next_collection_at = ?,
                    claim_token = NULL, claim_expires_at = NULL, last_success_at = ?,
                    failure_code = NULL, failure_message = NULL,
                    completed_at = CASE WHEN ? THEN completed_at ELSE ? END
                WHERE publication_id = ? AND claim_token = ?
                """, nextBucket, ts(next), ts(now), claim.manual(), next == null ? ts(now) : null,
                claim.publicationId(), claim.claimToken());
    }

    @Transactional
    public void fail(AnalyticsClaim claim, String code, String safeMessage, Duration backoff, Instant now) {
        jdbc.update("""
                UPDATE publication_analytics_states SET claim_token = NULL, claim_expires_at = NULL,
                    failure_code = ?, failure_message = ?,
                    next_collection_at = CASE WHEN ? THEN next_collection_at ELSE ? END
                WHERE publication_id = ? AND claim_token = ? AND claim_expires_at > ?
                """, code, safeMessage, claim.manual() && backoff != null,
                backoff == null ? null : ts(now.plus(backoff)),
                claim.publicationId(), claim.claimToken(), ts(now));
    }

    public PublicationAnalyticsState state(UUID workspaceId, UUID publicationId) {
        return jdbc.query("""
                SELECT * FROM publication_analytics_states WHERE workspace_id = ? AND publication_id = ?
                """, (rs, row) -> new PublicationAnalyticsState(
                instant(rs, "next_collection_at"), instant(rs, "last_attempt_at"),
                instant(rs, "last_success_at"), rs.getString("failure_code"),
                rs.getString("failure_message"), instant(rs, "completed_at")),
                workspaceId, publicationId).stream().findFirst().orElse(null);
    }

    public List<PublicationAnalyticsSnapshot> history(UUID workspaceId, UUID publicationId, int limit) {
        return jdbc.query("""
                SELECT * FROM publication_analytics_snapshots WHERE workspace_id = ? AND publication_id = ?
                ORDER BY collected_at DESC, id DESC LIMIT ?
                """, this::mapSnapshot, workspaceId, publicationId, Math.clamp(limit, 1, 100));
    }

    public List<PublicationAnalyticsSnapshot> latestForWorkspace(UUID workspaceId, Instant from, Instant to, int limit) {
        return jdbc.query("""
                SELECT latest.* FROM (
                    SELECT DISTINCT ON (s.publication_id) s.*
                    FROM publication_analytics_snapshots s
                    JOIN publications p ON p.id = s.publication_id
                    WHERE s.workspace_id = ? AND p.published_at >= ? AND p.published_at < ?
                    ORDER BY s.publication_id, s.collected_at DESC, s.id DESC
                ) latest ORDER BY latest.collected_at DESC, latest.id DESC LIMIT ?
                """, this::mapSnapshot, workspaceId, ts(from), ts(to), Math.clamp(limit, 1, 100));
    }

    private AnalyticsClaim mapClaim(ResultSet rs, boolean manual) throws SQLException {
        int bucket = rs.getInt("next_bucket");
        return new AnalyticsClaim(rs.getObject("publication_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("social_account_id", UUID.class), rs.getString("platform"),
                instant(rs, "published_at"), Math.min(bucket, 5), "AGE:" + bucket,
                rs.getObject("claim_token", UUID.class), manual);
    }

    private PublicationAnalyticsSnapshot mapSnapshot(ResultSet rs, int row) throws SQLException {
        return new PublicationAnalyticsSnapshot(rs.getObject("id", UUID.class),
                rs.getObject("publication_id", UUID.class), rs.getObject("social_account_id", UUID.class),
                rs.getString("provider"), rs.getString("bucket_key"), instant(rs, "collected_at"),
                instant(rs, "provider_observed_at"), rs.getLong("publication_age_seconds"),
                nullableLong(rs, "views"), nullableLong(rs, "reach"), nullableLong(rs, "likes"),
                nullableLong(rs, "comments"), nullableLong(rs, "shares"), nullableLong(rs, "saves"),
                nullableLong(rs, "total_interactions"), nullableLong(rs, "watch_time_ms"),
                nullableLong(rs, "average_watch_time_ms"), rs.getString("provider_metric_version"));
    }

    private Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private java.sql.Timestamp ts(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }
}
