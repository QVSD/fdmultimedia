package com.fdmultimedia.worker;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            availableMemoryBytes = linuxAvailableMemory(Path.of("/proc/meminfo"));
            if (availableMemoryBytes == null) {
                availableMemoryBytes = nonNegative(mxBean.getFreeMemorySize());
            }
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

    /**
     * Linux keeps recently used file data in the page cache, so MemFree is routinely close to zero on a healthy host while gigabytes are
     * instantly reclaimable. The scheduler treats low available memory as pressure and defers heavy Jobs, so reporting MemFree made a
     * Worker on a busy Linux host (or a Docker VM) refuse media Jobs for minutes. MemAvailable is the kernel's own estimate of memory
     * available without swapping and is what must be reported. Returns null when the file is absent or unreadable (non-Linux).
     */
    static Long linuxAvailableMemory(Path meminfo) {
        try {
            for (String line : Files.readAllLines(meminfo)) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        long kib = Long.parseLong(parts[1]);
                        return kib < 0 ? null : kib * 1024L;
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        return null;
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
