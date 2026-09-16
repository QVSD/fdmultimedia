package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerTelemetryCollectorTest {

    @Test
    void collectsCheapRuntimeTelemetryAndActiveJobs() {
        WorkerTelemetry telemetry = new WorkerTelemetryCollector().collect(new AtomicInteger(1));

        assertEquals(1, telemetry.activeJobs());
        assertNotNull(telemetry.jvmHeapUsedBytes());
        assertNotNull(telemetry.jvmHeapMaxBytes());
        assertTrue(telemetry.jvmHeapUsedBytes() >= 0);
        assertTrue(telemetry.jvmHeapMaxBytes() >= telemetry.jvmHeapUsedBytes());
        if (telemetry.systemCpuLoad() != null) {
            assertTrue(telemetry.systemCpuLoad() >= 0 && telemetry.systemCpuLoad() <= 1);
        }
        if (telemetry.processCpuLoad() != null) {
            assertTrue(telemetry.processCpuLoad() >= 0 && telemetry.processCpuLoad() <= 1);
        }
    }
}
