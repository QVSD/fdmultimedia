package com.fdmultimedia.api.jobs;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
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

    @Query(value = """
            SELECT job_type AS "jobType", COUNT(*) AS attempts,
                   COUNT(*) FILTER (WHERE outcome = 'SUCCEEDED') AS successes,
                   COUNT(*) FILTER (WHERE outcome <> 'SUCCEEDED') AS failures,
                   AVG(queue_wait_ms) AS "averageQueueWaitMs",
                   AVG(execution_ms) FILTER (WHERE outcome = 'SUCCEEDED') AS "averageExecutionMs",
                   AVG(total_latency_ms) AS "averageTotalLatencyMs"
            FROM job_execution_metrics
            WHERE workspace_id = :workspaceId AND created_at >= :since
            GROUP BY job_type ORDER BY job_type
            """, nativeQuery = true)
    List<JobTypeMetricView> aggregateByJobType(@Param("workspaceId") UUID workspaceId, @Param("since") Instant since);

    @Query(value = """
            SELECT m.worker_id AS "workerId", w.name AS "workerName", m.job_type AS "jobType",
                   COUNT(*) AS attempts,
                   COUNT(*) FILTER (WHERE m.outcome = 'SUCCEEDED') AS successes,
                   COUNT(*) FILTER (WHERE m.outcome <> 'SUCCEEDED') AS failures,
                   AVG(m.queue_wait_ms) AS "averageQueueWaitMs",
                   AVG(m.execution_ms) FILTER (WHERE m.outcome = 'SUCCEEDED') AS "averageExecutionMs",
                   AVG(m.total_latency_ms) AS "averageTotalLatencyMs",
                   MAX(m.created_at) AS "mostRecentExecutionAt"
            FROM job_execution_metrics m
            JOIN workers w ON w.id = m.worker_id
            WHERE m.workspace_id = :workspaceId AND m.created_at >= :since AND m.worker_id IS NOT NULL
            GROUP BY m.worker_id, w.name, m.job_type
            ORDER BY w.name, m.job_type
            """, nativeQuery = true)
    List<WorkerJobTypeMetricView> aggregateByWorkerAndJobType(
            @Param("workspaceId") UUID workspaceId, @Param("since") Instant since);
}
