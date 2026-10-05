package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerReliabilityContractTest {

    @Test
    void systemTestUsesTheSameLeaseRenewalBoundaryAsLongRunningJobs() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/fdmultimedia/worker/WorkerAgent.java"));

        assertTrue(source.contains("executeSystemTestJob(client, machineIdentifier, workerName, systemTestExecutor, job)"));
        int methodStart = source.indexOf("private static void executeSystemTestJob");
        int nextMethod = source.indexOf("private static void executeImportMediaJob", methodStart);
        String method = source.substring(methodStart, nextMethod);
        assertTrue(method.contains("leaseRenewLoop(client, machineIdentifier, job, running)"));
        assertTrue(method.contains("client.complete(job.jobId(), machineIdentifier, result)"));
        assertTrue(method.contains("renewer.interrupt()"));
    }
}
