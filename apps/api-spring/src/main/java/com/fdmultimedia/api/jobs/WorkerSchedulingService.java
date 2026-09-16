package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.workers.Worker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WorkerSchedulingService {

    private static final Logger log = LoggerFactory.getLogger(WorkerSchedulingService.class);

    private final JobExecutionMetricRepository metrics;
    private final WorkerSchedulingProperties properties;
    private final Clock clock;

    public WorkerSchedulingService(
            JobExecutionMetricRepository metrics,
            WorkerSchedulingProperties properties,
            Clock clock) {
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    public JobSchedulingDecision selectJob(Worker worker, List<Job> candidates, Instant now) {
        if (candidates.isEmpty()) {
            return JobSchedulingDecision.none("NO_COMPATIBLE_JOB");
        }
        if (isAtCapacity(worker, now)) {
            return JobSchedulingDecision.none("WORKER_AT_CAPACITY");
        }
        List<ScoredJob> scored = new ArrayList<>();
        for (Job candidate : candidates) {
            score(worker, candidate, now).ifPresent(scored::add);
        }
        if (scored.isEmpty()) {
            return JobSchedulingDecision.none("NO_JOB_PASSED_RESOURCE_THRESHOLDS");
        }
        ScoredJob selected = scored.stream()
                .max(Comparator
                        .comparingDouble(ScoredJob::score)
                        .thenComparing(scoredJob -> scoredJob.job().getQueuedAt(), Comparator.reverseOrder())
                        .thenComparing(scoredJob -> scoredJob.job().getId().toString(), Comparator.reverseOrder()))
                .orElseThrow();
        log.debug(
                "Scheduling policy {} selected job {} for worker {} with score {} reasons {}",
                properties.getPolicyId(),
                selected.job().getId(),
                worker.getId(),
                selected.score(),
                selected.reasonCodes());
        return JobSchedulingDecision.selected(selected.job(), selected.reasonCodes());
    }

    public boolean hasFreshTelemetry(Worker worker, Instant now) {
        Instant lastTelemetryAt = worker.getLastTelemetryAt();
        return lastTelemetryAt != null
                && !lastTelemetryAt.isBefore(now.minus(properties.getTelemetryFreshnessWindow()));
    }

    public boolean isAtCapacity(Worker worker, Instant now) {
        if (!hasFreshTelemetry(worker, now) || worker.getActiveJobs() == null) {
            return false;
        }
        return worker.getActiveJobs() >= worker.getMaxActiveJobs();
    }

    public String schedulingState(Worker worker) {
        Instant now = Instant.now(clock);
        if (!hasFreshTelemetry(worker, now)) {
            return worker.getLastTelemetryAt() == null ? "TELEMETRY_UNAVAILABLE" : "TELEMETRY_STALE";
        }
        if (isAtCapacity(worker, now)) {
            return "AT_CAPACITY";
        }
        if (hasCriticalMemoryPressure(worker)) {
            return "MEMORY_PRESSURE";
        }
        return "AVAILABLE";
    }

    private Optional<ScoredJob> score(Worker worker, Job job, Instant now) {
        List<String> reasons = new ArrayList<>();
        double score = 100;
        boolean telemetryFresh = hasFreshTelemetry(worker, now);
        boolean starved = isStarved(job, now);
        if (starved) {
            score += 10_000;
            reasons.add("STARVATION_PROTECTION");
        }
        if (telemetryFresh) {
            MemoryScore memory = memoryScore(worker, job, starved);
            if (memory.rejected()) {
                reasons.add("CRITICAL_MEMORY_PRESSURE");
                return Optional.empty();
            }
            score += memory.score();
            reasons.add(memory.reasonCode());
            if (worker.getSystemCpuLoad() == null) {
                reasons.add("CPU_UNAVAILABLE_NEUTRAL");
            } else {
                score += (1 - worker.getSystemCpuLoad()) * 10;
                reasons.add("CPU_LOAD_APPLIED");
            }
        } else {
            reasons.add(worker.getLastTelemetryAt() == null ? "TELEMETRY_UNAVAILABLE_FIFO" : "TELEMETRY_STALE_FIFO");
        }
        ExecutionHistoryStats history = metrics.executionHistory(
                worker.getId(),
                job.getType().name(),
                now.minus(properties.getHistoryWindow()));
        if (history.getSampleCount() >= properties.safeMinHistoricalSamples()
                && history.getAverageExecutionMs() != null) {
            double contribution = 10 / (1 + history.getAverageExecutionMs() / properties.safeHistoryReferenceExecutionMs());
            score += contribution;
            reasons.add("HISTORY_EXECUTION_MS_APPLIED");
        } else {
            reasons.add("HISTORY_INSUFFICIENT_SAMPLES");
        }
        reasons.add("FAILURE_RATE_DEFERRED");
        return Optional.of(new ScoredJob(job, score, reasons));
    }

    private boolean isStarved(Job job, Instant now) {
        Duration age = Duration.between(job.getQueuedAt(), now);
        return !age.isNegative() && age.compareTo(properties.getStarvationThreshold()) >= 0;
    }

    private MemoryScore memoryScore(Worker worker, Job job, boolean starved) {
        Long available = worker.getAvailableMemoryBytes();
        long total = worker.getTotalMemoryBytes();
        if (available == null || total <= 0) {
            return new MemoryScore(0, false, "MEMORY_UNAVAILABLE_NEUTRAL");
        }
        double ratio = (double) available / (double) total;
        if (ratio < properties.safeCriticalMemoryAvailableRatio() && workloadClass(job.getType()) != WorkloadClass.LIGHT && !starved) {
            return new MemoryScore(0, true, "CRITICAL_MEMORY_PRESSURE");
        }
        return new MemoryScore(Math.max(0, Math.min(ratio, 1)) * 20, false, "MEMORY_PRESSURE_APPLIED");
    }

    private boolean hasCriticalMemoryPressure(Worker worker) {
        Long available = worker.getAvailableMemoryBytes();
        return available != null
                && worker.getTotalMemoryBytes() > 0
                && ((double) available / (double) worker.getTotalMemoryBytes()) < properties.safeCriticalMemoryAvailableRatio();
    }

    private WorkloadClass workloadClass(JobType type) {
        return switch (type) {
            case SYSTEM_TEST, ANALYZE_HIGHLIGHTS -> WorkloadClass.LIGHT;
            case IMPORT_MEDIA, INSPECT_MEDIA, CREATE_CLIP, CREATE_SOCIAL_VERTICAL, TRANSCRIBE_MEDIA -> WorkloadClass.HEAVY;
        };
    }

    private enum WorkloadClass {
        LIGHT,
        HEAVY
    }

    private record MemoryScore(double score, boolean rejected, String reasonCode) {
    }

    private record ScoredJob(Job job, double score, List<String> reasonCodes) {
    }
}
