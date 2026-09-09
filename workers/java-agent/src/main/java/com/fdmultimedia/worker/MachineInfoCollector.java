package com.fdmultimedia.worker;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;

final class MachineInfoCollector {

    private static final String AGENT_VERSION = "fdm-worker-java/0.1.0";

    MachineInfo collect(String machineIdentifier, String workerName) {
        java.lang.management.OperatingSystemMXBean mxBean = ManagementFactory.getOperatingSystemMXBean();
        long memoryBytes = Runtime.getRuntime().maxMemory();
        if (mxBean instanceof OperatingSystemMXBean extendedMxBean) {
            memoryBytes = extendedMxBean.getTotalMemorySize();
        }

        return new MachineInfo(
                machineIdentifier,
                workerName,
                System.getProperty("os.name", "Unknown"),
                System.getProperty("os.arch", "unknown"),
                System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", System.getProperty("os.arch", "unknown")),
                Runtime.getRuntime().availableProcessors(),
                Math.max(1L, memoryBytes),
                blankToNull(System.getenv("FDM_WORKER_GPU_MODEL")),
                parseOptionalLong(System.getenv("FDM_WORKER_GPU_MEMORY_BYTES")),
                AGENT_VERSION);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Long parseOptionalLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value.trim());
    }
}
