package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MachineInfoCollectorTest {
    @Test
    void agentVersionIsTheFilteredReleaseVersion() {
        String version = MachineInfoCollector.releaseVersion();
        assertFalse(version.contains("${"), "resource filtering must have replaced the placeholder");
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?"), version);
        MachineInfo info = new MachineInfoCollector().collect("machine", "worker");
        assertEquals("fdm-worker-java/" + version, info.agentVersion());
    }
}
