package com.fdmultimedia.api.optimization;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.analytics.PublicationDashboardStore;
import java.math.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class OptimizationProposalStore {
    private final NamedParameterJdbcTemplate jdbc;
    public OptimizationProposalStore(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}

    public ReviewContext reviewContext(UUID workspaceId,UUID reviewId){
        String sql="""
                SELECT DISTINCT a.persona_id,a.persona_name_snapshot,sa.platform AS provider
                FROM campaign_performance_review_outputs o
                JOIN campaign_performance_reviews r ON r.id=o.review_id AND r.workspace_id=:workspaceId
                JOIN publication_attributions a ON a.publication_id=o.publication_id AND a.workspace_id=r.workspace_id
                JOIN publications p ON p.id=a.publication_id AND p.status='PUBLISHED'
                JOIN social_accounts sa ON sa.id=p.social_account_id
                WHERE o.review_id=:reviewId AND a.persona_id IS NOT NULL
                ORDER BY a.persona_id,sa.platform
                """;
        List<ReviewContext> rows=jdbc.query(sql,new MapSqlParameterSource("workspaceId",workspaceId).addValue("reviewId",reviewId),
                (rs,n)->new ReviewContext(rs.getObject("persona_id",UUID.class),rs.getString("persona_name_snapshot"),rs.getString("provider")));
        if(rows.isEmpty())return null;
        UUID persona=rows.get(0).personaId(); String provider=rows.get(0).provider();
        if(rows.stream().anyMatch(r->!persona.equals(r.personaId())||!provider.equals(r.provider())))
            throw new IllegalStateException("EVIDENCE_PROVIDER_OR_PERSONA_INCOMPATIBLE");
        return rows.get(0);
    }

    public Map<UUID,PersonaCohort> personaCohorts(UUID workspaceId,Set<UUID> personaIds,String provider,
            Window window,Metric metric,Instant from,Instant cutoff){
        String snapshot=PublicationDashboardStore.snapshotLateralJoinSql(window,"c.publication_id","c.published_at",
                "s."+metric.column()+" AS metric_value","snap"," AND s.collected_at<=:cutoff");
        String sql="""
                WITH cohort AS (
                    SELECT p.id AS publication_id,p.published_at,a.persona_id,a.persona_name_snapshot
                    FROM publications p
                    JOIN social_accounts sa ON sa.id=p.social_account_id
                    JOIN publication_attributions a ON a.publication_id=p.id AND a.workspace_id=p.workspace_id
                    WHERE p.workspace_id=:workspaceId AND p.status='PUBLISHED'
                      AND a.persona_id IN (:personaIds) AND sa.platform=:provider
                      AND p.published_at>=:cohortFrom AND p.published_at<=:cutoff
                ), observed AS (
                    SELECT c.*,(c.published_at<=:matureBefore) AS eligible,snap.id AS snapshot_id,snap.metric_value
                    FROM cohort c
                """+snapshot+") SELECT persona_id,max(persona_name_snapshot) AS persona_name,"
                +"COUNT(*) AS publication_count,COUNT(*) FILTER (WHERE eligible) AS eligible_count,"
                +"COUNT(snapshot_id) AS analytics_count,COUNT(metric_value) AS sample_count,"
                +"percentile_cont(0.5) WITHIN GROUP (ORDER BY metric_value)::numeric AS metric_median "
                +"FROM observed GROUP BY persona_id";
        MapSqlParameterSource p=new MapSqlParameterSource("workspaceId",workspaceId).addValue("personaIds",personaIds)
                .addValue("provider",provider).addValue("cohortFrom",Timestamp.from(from)).addValue("cutoff",Timestamp.from(cutoff))
                .addValue("matureBefore",Timestamp.from(cutoff.minusSeconds(window.targetSeconds())))
                .addValue("targetAge",window.targetSeconds()).addValue("minimumAge",window.minimumSeconds()).addValue("maximumAge",window.maximumSeconds());
        Map<UUID,PersonaCohort> result=new HashMap<>();
        jdbc.query(sql,p,rs->{UUID id=rs.getObject("persona_id",UUID.class);long eligible=rs.getLong("eligible_count"),sample=rs.getLong("sample_count");
            BigDecimal coverage=eligible==0?BigDecimal.ZERO:BigDecimal.valueOf(sample).divide(BigDecimal.valueOf(eligible),6,RoundingMode.HALF_UP);
            result.put(id,new PersonaCohort(id,rs.getString("persona_name"),rs.getInt("publication_count"),(int)eligible,
                    rs.getInt("analytics_count"),(int)sample,coverage,rs.getBigDecimal("metric_median")));});
        return result;
    }

    public record ReviewContext(UUID personaId,String personaName,String provider){}
    public record PersonaCohort(UUID personaId,String personaName,int publicationCount,int eligibleCount,
            int analyticsCount,int sampleCount,BigDecimal coverage,BigDecimal median){}
}
