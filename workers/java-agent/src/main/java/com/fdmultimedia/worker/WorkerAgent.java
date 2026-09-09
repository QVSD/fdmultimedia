package com.fdmultimedia.worker;

import java.time.Duration;

public final class WorkerAgent {

    private WorkerAgent() {
    }

    public static void main(String[] args) throws Exception {
        WorkerAgentConfig config = WorkerAgentConfig.fromEnvironment();
        String machineIdentifier = InstallationIdentity.loadOrCreate(config.identityFile());
        MachineInfo machineInfo = new MachineInfoCollector().collect(machineIdentifier, config.workerName());
        WorkerAgentClient client = new WorkerAgentClient(config.apiBaseUrl(), config.workerToken());

        while (!Thread.currentThread().isInterrupted()) {
            try {
                client.register(machineInfo);
                break;
            } catch (Exception ex) {
                System.err.println("Worker registration failed: " + ex.getMessage());
                sleep(backoff(config.heartbeatInterval()));
            }
        }

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
