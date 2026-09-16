package com.fdmultimedia.api.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.WorkerCredential;
import com.fdmultimedia.api.workers.WorkerRegistrationRequest;
import com.fdmultimedia.api.workers.WorkerTelemetryRequest;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerSchedulingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    private final JobExecutionMetricRepository metrics = mock(JobExecutionMetricRepository.class);
    private final WorkerSchedulingProperties properties = new WorkerSchedulingProperties();
    private final WorkerSchedulingService service = new WorkerSchedulingService(
            metrics,
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private Worker worker;

    @BeforeEach
    void setUp() {
        properties.setTelemetryFreshnessWindow(Duration.ofSeconds(60));
        properties.setMinHistoricalSamples(3);
        properties.setCriticalMemoryAvailableRatio(0.05);
        workspace = new Workspace("FD Multimedia", "fd-multimedia");
        WorkerCredential credential = new WorkerCredential(UUID.randomUUID(), workspace, "local-agent", "$2a$10$hash");
        worker = new Worker(workspace, credential, registration("machine-1", "Node A"), NOW.minusSeconds(5));
        worker.heartbeat(
                NOW.minusSeconds(1),
                new WorkerTelemetryRequest(0.5, 0.2, 16_000_000_000L, 100L, 200L, 0),
                List.of("SYSTEM_TEST", "IMPORT_MEDIA", "INSPECT_MEDIA"),
                List.of("DETERMINISTIC_V1"),
                1);
        when(metrics.executionHistory(any(), any(), any())).thenReturn(history(0, null));
    }

    @Test
    void selectsOldestCandidateWhenTelemetryAndHistoryAreNeutral() {
        Job older = job(JobType.SYSTEM_TEST, NOW.minusSeconds(20));
        Job newer = job(JobType.SYSTEM_TEST, NOW.minusSeconds(10));

        JobSchedulingDecision decision = service.selectJob(worker, List.of(newer, older), NOW);

        assertThat(decision.job()).contains(older);
        assertThat(decision.reasonCodes()).contains("CPU_LOAD_APPLIED", "HISTORY_INSUFFICIENT_SAMPLES");
    }

    @Test
    void refusesClaimWhenWorkerIsAtFreshCapacity() {
        worker.heartbeat(
                NOW.minusSeconds(1),
                new WorkerTelemetryRequest(0.5, 0.2, 16_000_000_000L, 100L, 200L, 1),
                List.of("SYSTEM_TEST"),
                List.of("DETERMINISTIC_V1"),
                1);

        JobSchedulingDecision decision = service.selectJob(worker, List.of(job(JobType.SYSTEM_TEST, NOW)), NOW);

        assertThat(decision.job()).isEmpty();
        assertThat(decision.reasonCodes()).containsExactly("WORKER_AT_CAPACITY");
    }

    @Test
    void appliesExecutionHistoryOnlyAfterMinimumSamples() {
        Job importJob = job(JobType.IMPORT_MEDIA, NOW.minusSeconds(5));
        Job inspectJob = job(JobType.INSPECT_MEDIA, NOW.minusSeconds(5));
        when(metrics.executionHistory(worker.getId(), "IMPORT_MEDIA", NOW.minus(properties.getHistoryWindow())))
                .thenReturn(history(5, 5_000.0));
        when(metrics.executionHistory(worker.getId(), "INSPECT_MEDIA", NOW.minus(properties.getHistoryWindow())))
                .thenReturn(history(5, 60_000.0));

        JobSchedulingDecision decision = service.selectJob(worker, List.of(inspectJob, importJob), NOW);

        assertThat(decision.job()).contains(importJob);
        assertThat(decision.reasonCodes()).contains("HISTORY_EXECUTION_MS_APPLIED");
    }

    @Test
    void criticalMemoryPressureRejectsHeavyJobsButAllowsStarvedWork() {
        worker.heartbeat(
                NOW.minusSeconds(1),
                new WorkerTelemetryRequest(0.5, 0.2, 100L, 100L, 200L, 0),
                List.of("IMPORT_MEDIA"),
                List.of("DETERMINISTIC_V1"),
                1);

        JobSchedulingDecision rejected = service.selectJob(
                worker,
                List.of(job(JobType.IMPORT_MEDIA, NOW.minusSeconds(20))),
                NOW);
        JobSchedulingDecision starved = service.selectJob(
                worker,
                List.of(job(JobType.IMPORT_MEDIA, NOW.minus(properties.getStarvationThreshold()).minusSeconds(1))),
                NOW);

        assertThat(rejected.job()).isEmpty();
        assertThat(rejected.reasonCodes()).containsExactly("NO_JOB_PASSED_RESOURCE_THRESHOLDS");
        assertThat(starved.job()).isPresent();
        assertThat(starved.reasonCodes()).contains("STARVATION_PROTECTION");
    }

    private Job job(JobType type, Instant queuedAt) {
        Map<String, Object> payload = switch (type) {
            case IMPORT_MEDIA, INSPECT_MEDIA -> Map.of("assetId", UUID.randomUUID().toString());
            default -> Map.of("message", "hello", "durationMs", 1L);
        };
        return new Job(workspace, type, payload, 3, queuedAt);
    }

    private ExecutionHistoryStats history(long sampleCount, Double averageExecutionMs) {
        return new ExecutionHistoryStats() {
            @Override
            public long getSampleCount() {
                return sampleCount;
            }

            @Override
            public Double getAverageExecutionMs() {
                return averageExecutionMs;
            }
        };
    }

    private WorkerRegistrationRequest registration(String machineIdentifier, String name) {
        return new WorkerRegistrationRequest(
                machineIdentifier,
                name,
                "Windows 11",
                "amd64",
                "AMD Ryzen",
                16,
                34_359_738_368L,
                null,
                null,
                "fdm-worker/0.1.0");
    }
}
