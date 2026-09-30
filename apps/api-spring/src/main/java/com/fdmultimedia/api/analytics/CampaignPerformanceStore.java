package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.CampaignPerformanceModels.*;
import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class CampaignPerformanceStore {
    private static final int MAX_GROUPS = 100;
    private final NamedParameterJdbcTemplate jdbc;

    public CampaignPerformanceStore(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<EvidenceRow> evidence(UUID workspaceId, UUID runId, Window window, Instant now) {
        String snapshot = PublicationDashboardStore.snapshotLateralJoinSql(window,
                "pub.publication_id", "pub.published_at",
                "s.views,s.reach,s.likes,s.comments,s.shares,s.saves,s.total_interactions", "snap");
        String sql = """
                WITH run_context AS (
                    SELECT rr.id, rr.workspace_id, rr.status, rr.campaign_plan_id
                    FROM robot_runs rr WHERE rr.id=:runId AND rr.workspace_id=:workspaceId
                ), output_base AS (
                    SELECT o.id AS output_id,o.selection_order,o.source_rank,o.status AS output_status,
                           o.highlight_candidate_id,o.content_draft_id,o.content_suggestion_id,o.publish_schedule_id
                    FROM robot_run_outputs o JOIN run_context rc ON rc.id=o.robot_run_id
                ), base AS (
                    SELECT * FROM output_base
                    UNION ALL
                    SELECT NULL::uuid,NULL::integer,NULL::integer,rc.status::text,NULL::uuid,NULL::uuid,NULL::uuid,NULL::uuid
                    FROM run_context rc WHERE NOT EXISTS (SELECT 1 FROM output_base)
                )
                SELECT b.*, pub.publication_id,pub.provider,pub.published_at,pub.robot_run_output_id,
                       COALESCE(pub.highlight_candidate_id,b.highlight_candidate_id) AS frozen_candidate_id,
                       COALESCE(pub.selection_order,b.selection_order) AS frozen_selection_order,
                       COALESCE(pub.source_rank,b.source_rank) AS frozen_source_rank,
                       COALESCE(pub.content_draft_id,b.content_draft_id) AS frozen_draft_id,
                       COALESCE(pub.applied_content_suggestion_id,b.content_suggestion_id) AS frozen_suggestion_id,
                       COALESCE(pub.publish_schedule_id,b.publish_schedule_id) AS frozen_schedule_id,
                       pub.campaign_plan_id,pub.campaign_plan_revision,pub.campaign_plan_item_id,
                       pub.campaign_copy_set_id,pub.campaign_copy_set_revision,pub.campaign_copy_item_id,
                       COALESCE(pub.campaign_role, current_item.role) AS campaign_role,
                       snap.id AS snapshot_id,snap.views,snap.reach,snap.likes,snap.comments,snap.shares,snap.saves,snap.total_interactions,
                       CASE WHEN pub.publication_id IS NULL THEN 'UNPUBLISHED'
                            WHEN pub.published_at > :matureBefore THEN 'TOO_YOUNG'
                            WHEN snap.id IS NULL THEN 'MISSING_SNAPSHOT' ELSE 'OBSERVED' END AS evidence_status
                FROM base b
                CROSS JOIN run_context rc
                LEFT JOIN campaign_content_plan_items current_item
                  ON current_item.plan_id=rc.campaign_plan_id AND current_item.robot_run_output_id=b.output_id
                LEFT JOIN LATERAL (
                    SELECT p.id AS publication_id,sa.platform AS provider,p.published_at,
                           a.robot_run_output_id,a.highlight_candidate_id,a.selection_order,a.source_rank,
                           a.content_draft_id,a.applied_content_suggestion_id,a.publish_schedule_id,
                           a.campaign_plan_id,a.campaign_plan_revision,a.campaign_plan_item_id,
                           a.campaign_copy_set_id,a.campaign_copy_set_revision,a.campaign_copy_item_id,
                           cpi.role AS campaign_role
                    FROM publication_attributions a
                    JOIN publications p ON p.id=a.publication_id AND p.status='PUBLISHED'
                    JOIN social_accounts sa ON sa.id=p.social_account_id
                    LEFT JOIN campaign_content_plan_items cpi ON cpi.id=a.campaign_plan_item_id
                    WHERE a.workspace_id=:workspaceId AND a.robot_run_id=:runId
                      AND ((b.output_id IS NOT NULL AND a.robot_run_output_id=b.output_id)
                        OR (b.output_id IS NULL AND a.robot_run_output_id IS NULL))
                    ORDER BY p.published_at ASC,p.id ASC LIMIT 1
                ) pub ON true
                """ + snapshot + " ORDER BY b.selection_order NULLS LAST,pub.publication_id";
        return jdbc.query(sql, params(workspaceId,runId,window,now), (rs,n)->evidence(rs));
    }

    public CohortRows cohorts(UUID workspaceId, LocalDate from, LocalDate to, Window window,
            Metric metric, Dimension dimension, String provider, Instant now) {
        String key = dimension==Dimension.ROLE ? "COALESCE(campaign_role,'NONE')" : "coordination_policy";
        String label = dimension==Dimension.ROLE
                ? "CASE WHEN campaign_role IS NULL THEN 'No campaign role' ELSE campaign_role END" : "coordination_policy";
        String snapshot = PublicationDashboardStore.snapshotLateralJoinSql(window,"c.publication_id","c.published_at",
                "s."+metric.column()+" AS metric_value","snap");
        String sql = """
                WITH cohort AS (
                    SELECT p.id AS publication_id,p.published_at,sa.platform AS provider,
                           cpi.role AS campaign_role,rr.copy_coordination_policy_snapshot AS coordination_policy
                    FROM publications p
                    JOIN social_accounts sa ON sa.id=p.social_account_id
                    JOIN publication_attributions a ON a.publication_id=p.id AND a.workspace_id=p.workspace_id
                    JOIN robot_runs rr ON rr.id=a.robot_run_id
                    LEFT JOIN campaign_content_plan_items cpi ON cpi.id=a.campaign_plan_item_id
                    WHERE p.workspace_id=:workspaceId AND p.status='PUBLISHED'
                      AND p.published_at>=:fromInstant AND p.published_at<:toExclusive
                      AND (CAST(:provider AS text) IS NULL OR sa.platform=:provider)
                ), observed AS (
                    SELECT c.*,(c.published_at<=:matureBefore) AS eligible,snap.id AS snapshot_id,snap.metric_value
                    FROM cohort c
                """ + snapshot + ") SELECT "+key+" AS dimension_key,"+label+" AS dimension_label,"
                + "COUNT(*) AS publication_count,COUNT(*) FILTER (WHERE eligible) AS eligible_count,"
                + "COUNT(snapshot_id) AS analytics_count,COUNT(metric_value) AS sample_count,"
                + "SUM(metric_value) AS metric_total,AVG(metric_value) AS metric_average,"
                + "percentile_cont(0.5) WITHIN GROUP (ORDER BY metric_value)::numeric AS metric_median,"
                + "MIN(metric_value) AS metric_minimum,MAX(metric_value) AS metric_maximum "
                + "FROM observed GROUP BY "+key+","+label+" ORDER BY dimension_label LIMIT "+(MAX_GROUPS+1);
        MapSqlParameterSource p=params(workspaceId,null,window,now)
                .addValue("fromInstant",Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)))
                .addValue("toExclusive",Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)))
                .addValue("provider",provider);
        List<CohortRow> rows=jdbc.query(sql,p,(rs,n)->cohort(rs));
        boolean truncated=rows.size()>MAX_GROUPS;
        return new CohortRows(truncated?rows.subList(0,MAX_GROUPS):rows,truncated);
    }

    public List<CampaignOption> campaignOptions(UUID workspaceId,int limit){
        String sql="""
                SELECT rr.id,r.name,rr.status,rr.started_at,rr.requested_output_count,
                       COALESCE(rr.actual_output_count,(SELECT count(*) FROM robot_run_outputs o WHERE o.robot_run_id=rr.id)) AS actual_count,
                       (SELECT count(*) FROM publication_attributions a JOIN publications p ON p.id=a.publication_id
                         WHERE a.robot_run_id=rr.id AND p.status='PUBLISHED') AS published_count,
                       (SELECT pr.id FROM campaign_performance_reviews pr WHERE pr.robot_run_id=rr.id
                         ORDER BY pr.created_at DESC,pr.id DESC LIMIT 1) AS latest_review_id
                FROM robot_runs rr JOIN robots r ON r.id=rr.robot_id
                WHERE rr.workspace_id=:workspaceId AND rr.status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELLED')
                ORDER BY rr.started_at DESC,rr.id DESC LIMIT :limit
                """;
        return jdbc.query(sql,new MapSqlParameterSource("workspaceId",workspaceId).addValue("limit",Math.min(Math.max(limit,1),100)),
                (rs,n)->new CampaignOption(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("status"),
                        rs.getTimestamp("started_at").toInstant(),rs.getInt("requested_output_count"),rs.getInt("actual_count"),
                        rs.getInt("published_count"),rs.getObject("latest_review_id",UUID.class)));
    }

    private static EvidenceRow evidence(ResultSet rs)throws SQLException{
        return new EvidenceRow(rs.getObject("output_id",UUID.class),integer(rs,"frozen_selection_order"),integer(rs,"frozen_source_rank"),
                rs.getString("output_status"),rs.getString("campaign_role"),rs.getObject("frozen_candidate_id",UUID.class),
                rs.getObject("campaign_plan_id",UUID.class),integer(rs,"campaign_plan_revision"),rs.getObject("campaign_plan_item_id",UUID.class),
                rs.getObject("campaign_copy_set_id",UUID.class),integer(rs,"campaign_copy_set_revision"),rs.getObject("campaign_copy_item_id",UUID.class),
                rs.getObject("frozen_suggestion_id",UUID.class),rs.getObject("frozen_draft_id",UUID.class),rs.getObject("frozen_schedule_id",UUID.class),
                rs.getObject("publication_id",UUID.class),rs.getString("provider"),instant(rs,"published_at"),rs.getObject("snapshot_id",UUID.class),
                OutputEvidenceStatus.valueOf(rs.getString("evidence_status")),lng(rs,"views"),lng(rs,"reach"),lng(rs,"likes"),lng(rs,"comments"),
                lng(rs,"shares"),lng(rs,"saves"),lng(rs,"total_interactions"));
    }

    private static CohortRow cohort(ResultSet rs)throws SQLException{
        long eligible=rs.getLong("eligible_count"),sample=rs.getLong("sample_count");
        BigDecimal coverage=eligible==0?null:BigDecimal.valueOf(sample).divide(BigDecimal.valueOf(eligible),4,java.math.RoundingMode.HALF_UP);
        return new CohortRow(rs.getString("dimension_key"),rs.getString("dimension_label"),rs.getLong("publication_count"),eligible,
                rs.getLong("analytics_count"),sample,coverage,new MetricStatistics(rs.getBigDecimal("metric_total"),
                        rs.getBigDecimal("metric_average"),rs.getBigDecimal("metric_median"),rs.getBigDecimal("metric_minimum"),
                        rs.getBigDecimal("metric_maximum"),sample));
    }

    private static MapSqlParameterSource params(UUID workspaceId,UUID runId,Window window,Instant now){
        Instant matureBefore=now.minusSeconds(window.targetSeconds());
        return new MapSqlParameterSource("workspaceId",workspaceId).addValue("runId",runId)
                .addValue("matureBefore",Timestamp.from(matureBefore)).addValue("targetAge",window.targetSeconds())
                .addValue("minimumAge",window.minimumSeconds()).addValue("maximumAge",window.maximumSeconds());
    }
    private static Integer integer(ResultSet rs,String c)throws SQLException{int v=rs.getInt(c);return rs.wasNull()?null:v;}
    private static Long lng(ResultSet rs,String c)throws SQLException{long v=rs.getLong(c);return rs.wasNull()?null:v;}
    private static Instant instant(ResultSet rs,String c)throws SQLException{Timestamp v=rs.getTimestamp(c);return v==null?null:v.toInstant();}

    public record EvidenceRow(UUID robotRunOutputId,Integer selectionOrder,Integer sourceRank,String outputStatus,String campaignRole,
            UUID highlightCandidateId,UUID campaignPlanId,Integer campaignPlanRevision,UUID campaignPlanItemId,
            UUID campaignCopySetId,Integer campaignCopySetRevision,UUID campaignCopyItemId,UUID contentSuggestionId,
            UUID contentDraftId,UUID publishScheduleId,UUID publicationId,String provider,Instant publishedAt,UUID analyticsSnapshotId,
            OutputEvidenceStatus evidenceStatus,Long views,Long reach,Long likes,Long comments,Long shares,Long saves,Long totalInteractions){
        public Long metric(Metric metric){return switch(metric){case VIEWS->views;case REACH->reach;case LIKES->likes;case COMMENTS->comments;case SHARES->shares;case SAVES->saves;case TOTAL_INTERACTIONS->totalInteractions;};}
    }
    public record CohortRows(List<CohortRow> rows,boolean truncated){}
}
