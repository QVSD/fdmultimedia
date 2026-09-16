package com.fdmultimedia.worker;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class WorkerTelemetryCollector {

    WorkerTelemetry collect(AtomicInteger activeJobs) {
        java.lang.management.OperatingSystemMXBean baseMxBean = ManagementFactory.getOperatingSystemMXBean();
        Runtime runtime = Runtime.getRuntime();
        Double systemCpuLoad = null;
        Double processCpuLoad = null;
        Long availableMemoryBytes = null;
        if (baseMxBean instanceof OperatingSystemMXBean mxBean) {
            systemCpuLoad = normalizeLoad(mxBean.getCpuLoad());
            processCpuLoad = normalizeLoad(mxBean.getProcessCpuLoad());
            availableMemoryBytes = nonNegative(mxBean.getFreeMemorySize());
        }
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        return new WorkerTelemetry(
                systemCpuLoad,
                processCpuLoad,
                availableMemoryBytes,
                nonNegative(heapUsed),
                nonNegative(runtime.maxMemory()),
                Math.max(0, activeJobs.get()));
    }

    private Double normalizeLoad(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            return null;
        }
        return value;
    }

    private Long nonNegative(long value) {
        return value < 0 ? null : value;
    }
}
