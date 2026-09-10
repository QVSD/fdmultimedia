package com.fdmultimedia.worker;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WorkerAgent {

    private WorkerAgent() {
    }

    public static void main(String[] args) throws Exception {
        WorkerAgentConfig config = WorkerAgentConfig.fromEnvironment();
        String machineIdentifier = InstallationIdentity.loadOrCreate(config.identityFile());
        MachineInfo machineInfo = new MachineInfoCollector().collect(machineIdentifier, config.workerName());
        WorkerAgentClient client = new WorkerAgentClient(config.apiBaseUrl(), config.workerToken());
        SystemTestExecutor systemTestExecutor = new SystemTestExecutor();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                client.register(machineInfo);
                break;
            } catch (Exception ex) {
                System.err.println("Worker registration failed: " + ex.getMessage());
                sleep(backoff(config.heartbeatInterval()));
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executor.shutdownNow();
            shutdown.countDown();
        }));
        executor.submit(() -> heartbeatLoop(client, machineIdentifier, config));
        executor.submit(() -> jobLoop(client, machineIdentifier, config, systemTestExecutor));
        shutdown.await();
    }

    private static void heartbeatLoop(WorkerAgentClient client, String machineIdentifier, WorkerAgentConfig config) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                client.heartbeat(machineIdentifier);
                sleep(config.heartbeatInterval());
            } catch (Exception ex) {
                System.err.println("Worker heartbeat failed: " + ex.getMessage());
                sleep(backoff(config.heartbeatInterval()));
            }
        }
    }

    private static void jobLoop(
            WorkerAgentClient client,
            String machineIdentifier,
            WorkerAgentConfig config,
            SystemTestExecutor systemTestExecutor) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ClaimedJob job = client.claim(machineIdentifier);
                if (!job.available()) {
                    sleep(config.jobPollInterval());
                    continue;
                }
                executeClaimedJob(client, machineIdentifier, config.workerName(), systemTestExecutor, job);
            } catch (Exception ex) {
                System.err.println("Worker job polling failed: " + ex.getMessage());
                sleep(backoff(config.jobPollInterval()));
            }
        }
    }

    private static void executeClaimedJob(
            WorkerAgentClient client,
            String machineIdentifier,
            String workerName,
            SystemTestExecutor systemTestExecutor,
            ClaimedJob job) throws InterruptedException {
        try {
            client.started(job.jobId(), machineIdentifier);
            Map<String, Object> result = systemTestExecutor.execute(job, workerName);
            client.complete(job.jobId(), machineIdentifier, result);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } catch (Exception ex) {
            try {
                client.fail(job.jobId(), machineIdentifier, "SYSTEM_TEST_FAILED", ex.getMessage());
            } catch (Exception reportFailure) {
                System.err.println("Worker failed to report job failure: " + reportFailure.getMessage());
            }
        }
    }

    private static Duration backoff(Duration heartbeatInterval) {
        return heartbeatInterval.multipliedBy(2).compareTo(Duration.ofSeconds(30)) > 0
                ? Duration.ofSeconds(30)
                : heartbeatInterval.multipliedBy(2);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
