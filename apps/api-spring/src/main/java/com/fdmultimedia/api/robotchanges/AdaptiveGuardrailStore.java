package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.Observation;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class AdaptiveGuardrailStore {
    private final NamedParameterJdbcTemplate jdbc;
    public AdaptiveGuardrailStore(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}

    public Observation observation(UUID workspaceId,UUID robotId,Instant epoch,Instant now){
        Instant matureBefore=now.minusSeconds(259_200);
        var p=new MapSqlParameterSource("workspace",workspaceId).addValue("robot",robotId)
                .addValue("epoch",Timestamp.from(epoch)).addValue("matureBefore",Timestamp.from(matureBefore))
                .addValue("minAge",216_000L).addValue("maxAge",345_600L).addValue("targetAge",259_200L);
        return jdbc.queryForObject("""
                WITH post_runs AS (
                  SELECT id FROM robot_runs WHERE workspace_id=:workspace AND robot_id=:robot AND created_at>=:epoch
                ), pubs AS (
                  SELECT DISTINCT p.id,p.published_at
                  FROM post_runs r JOIN publication_attributions a ON a.robot_run_id=r.id AND a.workspace_id=:workspace
                  JOIN publications p ON p.id=a.publication_id AND p.status='PUBLISHED'
                ), observed AS (
                  SELECT p.id,p.published_at,(p.published_at<=:matureBefore) eligible,s.id snapshot_id,s.total_interactions
                  FROM pubs p LEFT JOIN LATERAL (
                    SELECT s.id,s.total_interactions FROM publication_analytics_snapshots s
                    WHERE s.publication_id=p.id AND p.published_at<=:matureBefore
                      AND s.publication_age_seconds BETWEEN :minAge AND :maxAge
                    ORDER BY ABS(s.publication_age_seconds-:targetAge),s.collected_at DESC,s.id DESC LIMIT 1
                  ) s ON TRUE
                )
                SELECT (SELECT count(*) FROM post_runs) runs,count(*) publications,
                       count(*) FILTER (WHERE eligible) eligible,
                       count(snapshot_id) analytics_count,count(total_interactions) metric_samples
                FROM observed
                """,p,(rs,row)->{
            int eligible=rs.getInt("eligible"),samples=rs.getInt("metric_samples");
            BigDecimal coverage=eligible==0?null:BigDecimal.valueOf(samples).divide(BigDecimal.valueOf(eligible),6,java.math.RoundingMode.HALF_UP);
            return new Observation(rs.getInt("runs"),rs.getInt("publications"),eligible,
                    rs.getInt("analytics_count"),samples,coverage);
        });
    }
}
