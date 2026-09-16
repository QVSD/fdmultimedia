package com.fdmultimedia.api.jobs;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobExecutionMetricRepository extends JpaRepository<JobExecutionMetric, UUID> {

    Optional<JobExecutionMetric> findByJobIdAndAttempt(UUID jobId, int attempt);

    @Query(
            value = """
                    SELECT COUNT(*) AS "sampleCount", AVG(execution_ms) AS "averageExecutionMs"
                    FROM job_execution_metrics
                    WHERE worker_id = :workerId
                      AND job_type = :jobType
                      AND outcome = 'SUCCEEDED'
                      AND execution_ms IS NOT NULL
                      AND created_at >= :since
                    """,
            nativeQuery = true)
    ExecutionHistoryStats executionHistory(
            @Param("workerId") UUID workerId,
            @Param("jobType") String jobType,
            @Param("since") Instant since);
}
