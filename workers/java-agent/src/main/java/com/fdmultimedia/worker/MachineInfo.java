package com.fdmultimedia.worker;

record MachineInfo(
        String machineIdentifier,
        String name,
        String operatingSystem,
        String architecture,
        String cpuModel,
        int cpuLogicalCores,
        long totalMemoryBytes,
        String gpuModel,
        Long gpuMemoryBytes,
        String agentVersion) {
}
