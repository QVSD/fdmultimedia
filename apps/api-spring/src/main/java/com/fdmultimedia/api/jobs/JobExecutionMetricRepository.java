package com.fdmultimedia.api.jobs;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobExecutionMetricRepository extends JpaRepository<JobExecutionMetric, UUID> {

    Optional<JobExecutionMetric> findByJobIdAndAttempt(UUID jobId, int attempt);
}
