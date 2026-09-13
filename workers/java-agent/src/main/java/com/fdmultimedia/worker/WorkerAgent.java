package com.fdmultimedia.worker;

import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorkerAgent {

    private WorkerAgent() {
    }

    public static void main(String[] args) throws Exception {
        WorkerAgentConfig config = WorkerAgentConfig.fromEnvironment();
        String machineIdentifier = InstallationIdentity.loadOrCreate(config.identityFile());
        MachineInfo machineInfo = new MachineInfoCollector().collect(machineIdentifier, config.workerName());
        WorkerAgentClient client = new WorkerAgentClient(config.apiBaseUrl(), config.workerToken());
        SystemTestExecutor systemTestExecutor = new SystemTestExecutor();
        ImportMediaExecutor importMediaExecutor = new ImportMediaExecutor();
        AnalyzeHighlightsExecutor analyzeHighlightsExecutor = new AnalyzeHighlightsExecutor(new DeterministicHighlightAnalyzer());
        boolean ffprobeAvailable = FfprobeSupport.isAvailable(config.ffprobePath());
        if (ffprobeAvailable) {
            System.err.println("FFprobe available at " + config.ffprobePath());
        } else {
            System.err.println("FFprobe unavailable; INSPECT_MEDIA capability disabled");
        }
        boolean ffmpegAvailable = FfmpegSupport.isAvailable(config.ffmpegPath());
        if (ffmpegAvailable) {
            System.err.println("FFmpeg available at " + config.ffmpegPath());
        } else {
            System.err.println("FFmpeg unavailable; CREATE_CLIP and CREATE_SOCIAL_VERTICAL capabilities disabled");
        }
        InspectMediaExecutor inspectMediaExecutor = ffprobeAvailable ? new InspectMediaExecutor(config.ffprobePath()) : null;
        FfmpegClipExecutor clipExecutor = ffmpegAvailable ? new FfmpegClipExecutor(config.ffmpegPath()) : null;
        List<String> supportedJobTypes = supportedJobTypes(ffprobeAvailable, ffmpegAvailable);

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
        executor.submit(() -> jobLoop(client, machineIdentifier, config, systemTestExecutor, importMediaExecutor, inspectMediaExecutor, clipExecutor, analyzeHighlightsExecutor, supportedJobTypes));
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
            SystemTestExecutor systemTestExecutor,
            ImportMediaExecutor importMediaExecutor,
            InspectMediaExecutor inspectMediaExecutor,
            FfmpegClipExecutor clipExecutor,
            AnalyzeHighlightsExecutor analyzeHighlightsExecutor,
            List<String> supportedJobTypes) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ClaimedJob job = client.claim(machineIdentifier, supportedJobTypes);
                if (!job.available()) {
                    sleep(config.jobPollInterval());
                    continue;
                }
                executeClaimedJob(client, machineIdentifier, config.workerName(), systemTestExecutor, importMediaExecutor, inspectMediaExecutor, clipExecutor, analyzeHighlightsExecutor, job);
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
            ImportMediaExecutor importMediaExecutor,
            InspectMediaExecutor inspectMediaExecutor,
            FfmpegClipExecutor clipExecutor,
            AnalyzeHighlightsExecutor analyzeHighlightsExecutor,
            ClaimedJob job) throws InterruptedException {
        try {
            client.started(job.jobId(), machineIdentifier);
            if ("IMPORT_MEDIA".equals(job.type())) {
                executeImportMediaJob(client, machineIdentifier, importMediaExecutor, job);
            } else if ("INSPECT_MEDIA".equals(job.type()) && inspectMediaExecutor != null) {
                executeInspectMediaJob(client, machineIdentifier, inspectMediaExecutor, job);
            } else if ("CREATE_CLIP".equals(job.type()) && clipExecutor != null) {
                executeClipJob(client, machineIdentifier, clipExecutor, job);
            } else if ("CREATE_SOCIAL_VERTICAL".equals(job.type()) && clipExecutor != null) {
                executeSocialVerticalJob(client, machineIdentifier, clipExecutor, job);
            } else if ("ANALYZE_HIGHLIGHTS".equals(job.type())) {
                executeAnalyzeHighlightsJob(client, machineIdentifier, analyzeHighlightsExecutor, job);
            } else if ("SYSTEM_TEST".equals(job.type())) {
                Map<String, Object> result = systemTestExecutor.execute(job, workerName);
                client.complete(job.jobId(), machineIdentifier, result);
            } else {
                client.fail(job.jobId(), machineIdentifier, "UNSUPPORTED_JOB_TYPE", "Worker does not support " + job.type(), true);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } catch (Exception ex) {
            try {
                client.fail(job.jobId(), machineIdentifier, "JOB_FAILED", ex.getMessage(), false);
            } catch (Exception reportFailure) {
                System.err.println("Worker failed to report job failure: " + reportFailure.getMessage());
            }
        }
    }

    private static void executeImportMediaJob(
            WorkerAgentClient client,
            String machineIdentifier,
            ImportMediaExecutor importMediaExecutor,
            ClaimedJob job) throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread renewer = new Thread(() -> leaseRenewLoop(client, machineIdentifier, job, running), "fdm-job-lease-renewer");
        renewer.setDaemon(true);
        renewer.start();
        try {
            importMediaExecutor.execute(client, job, machineIdentifier);
        } catch (ImportFailureException ex) {
            reportImportFailure(client, machineIdentifier, job, ex.code(), ex.getMessage(), ex.terminal());
        } catch (Exception ex) {
            reportImportFailure(client, machineIdentifier, job, "IMPORT_MEDIA_FAILED", ex.getMessage(), false);
        } finally {
            running.set(false);
            renewer.interrupt();
        }
    }

    private static void executeInspectMediaJob(
            WorkerAgentClient client,
            String machineIdentifier,
            InspectMediaExecutor inspectMediaExecutor,
            ClaimedJob job) throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread renewer = new Thread(() -> leaseRenewLoop(client, machineIdentifier, job, running), "fdm-job-lease-renewer");
        renewer.setDaemon(true);
        renewer.start();
        try {
            inspectMediaExecutor.execute(client, job, machineIdentifier);
        } catch (ImportFailureException ex) {
            reportInspectionFailure(client, machineIdentifier, job, ex.code(), ex.getMessage(), ex.terminal());
        } catch (Exception ex) {
            reportInspectionFailure(client, machineIdentifier, job, "INSPECT_MEDIA_FAILED", ex.getMessage(), false);
        } finally {
            running.set(false);
            renewer.interrupt();
        }
    }

    private static void executeClipJob(
            WorkerAgentClient client,
            String machineIdentifier,
            FfmpegClipExecutor clipExecutor,
            ClaimedJob job) throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread renewer = new Thread(() -> leaseRenewLoop(client, machineIdentifier, job, running), "fdm-job-lease-renewer");
        renewer.setDaemon(true);
        renewer.start();
        try {
            clipExecutor.execute(client, job, machineIdentifier);
        } catch (ImportFailureException ex) {
            reportClipFailure(client, machineIdentifier, job, ex.code(), ex.getMessage(), ex.terminal());
        } catch (Exception ex) {
            reportClipFailure(client, machineIdentifier, job, "CREATE_CLIP_FAILED", ex.getMessage(), false);
        } finally {
            running.set(false);
            renewer.interrupt();
        }
    }

    private static void executeSocialVerticalJob(
            WorkerAgentClient client,
            String machineIdentifier,
            FfmpegClipExecutor clipExecutor,
            ClaimedJob job) throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread renewer = new Thread(() -> leaseRenewLoop(client, machineIdentifier, job, running), "fdm-job-lease-renewer");
        renewer.setDaemon(true);
        renewer.start();
        try {
            clipExecutor.executeSocialVertical(client, job, machineIdentifier);
        } catch (ImportFailureException ex) {
            reportSocialVerticalFailure(client, machineIdentifier, job, ex.code(), ex.getMessage(), ex.terminal());
        } catch (Exception ex) {
            reportSocialVerticalFailure(client, machineIdentifier, job, "CREATE_SOCIAL_VERTICAL_FAILED", ex.getMessage(), false);
        } finally {
            running.set(false);
            renewer.interrupt();
        }
    }

    private static void executeAnalyzeHighlightsJob(
            WorkerAgentClient client,
            String machineIdentifier,
            AnalyzeHighlightsExecutor analyzeHighlightsExecutor,
            ClaimedJob job) throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread renewer = new Thread(() -> leaseRenewLoop(client, machineIdentifier, job, running), "fdm-job-lease-renewer");
        renewer.setDaemon(true);
        renewer.start();
        try {
            analyzeHighlightsExecutor.execute(client, job, machineIdentifier);
        } catch (ImportFailureException ex) {
            reportHighlightFailure(client, machineIdentifier, job, ex.code(), ex.getMessage(), ex.terminal());
        } catch (Exception ex) {
            reportHighlightFailure(client, machineIdentifier, job, "ANALYZE_HIGHLIGHTS_FAILED", ex.getMessage(), false);
        } finally {
            running.set(false);
            renewer.interrupt();
        }
    }

    private static void leaseRenewLoop(
            WorkerAgentClient client,
            String machineIdentifier,
            ClaimedJob job,
            AtomicBoolean running) {
        Duration interval = Duration.ofSeconds(Math.max(1, job.leaseSeconds() / 2));
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            sleep(interval);
            if (!running.get()) {
                return;
            }
            try {
                client.renew(job.jobId(), machineIdentifier);
            } catch (Exception ex) {
                System.err.println("Worker failed to renew job lease: " + ex.getMessage());
            }
        }
    }

    private static void reportImportFailure(
            WorkerAgentClient client,
            String machineIdentifier,
            ClaimedJob job,
            String code,
            String message,
            boolean terminal) {
        Object assetId = job.payload().get("assetId");
        try {
            if (assetId == null) {
                client.fail(job.jobId(), machineIdentifier, code, message, terminal);
            } else {
                client.failImport(job.jobId(), machineIdentifier, java.util.UUID.fromString(String.valueOf(assetId)), code, message, terminal);
            }
        } catch (Exception reportFailure) {
            System.err.println("Worker failed to report import failure: " + reportFailure.getMessage());
        }
    }

    private static void reportInspectionFailure(
            WorkerAgentClient client,
            String machineIdentifier,
            ClaimedJob job,
            String code,
            String message,
            boolean terminal) {
        Object assetId = job.payload().get("assetId");
        try {
            if (assetId == null) {
                client.fail(job.jobId(), machineIdentifier, code, message, terminal);
            } else {
                client.failInspection(job.jobId(), machineIdentifier, java.util.UUID.fromString(String.valueOf(assetId)), code, message, terminal);
            }
        } catch (Exception reportFailure) {
            System.err.println("Worker failed to report inspection failure: " + reportFailure.getMessage());
        }
    }

    private static void reportClipFailure(
            WorkerAgentClient client,
            String machineIdentifier,
            ClaimedJob job,
            String code,
            String message,
            boolean terminal) {
        Object sourceAssetId = job.payload().get("sourceAssetId");
        Object outputAssetId = job.payload().get("outputAssetId");
        try {
            if (sourceAssetId == null || outputAssetId == null) {
                client.fail(job.jobId(), machineIdentifier, code, message, terminal);
            } else {
                client.failClip(
                        job.jobId(),
                        machineIdentifier,
                        java.util.UUID.fromString(String.valueOf(sourceAssetId)),
                        java.util.UUID.fromString(String.valueOf(outputAssetId)),
                        code,
                        message,
                        terminal);
            }
        } catch (Exception reportFailure) {
            System.err.println("Worker failed to report clip failure: " + reportFailure.getMessage());
        }
    }

    private static void reportSocialVerticalFailure(
            WorkerAgentClient client,
            String machineIdentifier,
            ClaimedJob job,
            String code,
            String message,
            boolean terminal) {
        Object sourceAssetId = job.payload().get("sourceAssetId");
        Object outputAssetId = job.payload().get("outputAssetId");
        try {
            if (sourceAssetId == null || outputAssetId == null) {
                client.fail(job.jobId(), machineIdentifier, code, message, terminal);
            } else {
                client.failSocialVertical(
                        job.jobId(),
                        machineIdentifier,
                        java.util.UUID.fromString(String.valueOf(sourceAssetId)),
                        java.util.UUID.fromString(String.valueOf(outputAssetId)),
                        code,
                        message,
                        terminal);
            }
        } catch (Exception reportFailure) {
            System.err.println("Worker failed to report social vertical failure: " + reportFailure.getMessage());
        }
    }

    private static void reportHighlightFailure(
            WorkerAgentClient client,
            String machineIdentifier,
            ClaimedJob job,
            String code,
            String message,
            boolean terminal) {
        Object assetId = job.payload().get("assetId");
        try {
            if (assetId == null) {
                client.fail(job.jobId(), machineIdentifier, code, message, terminal);
            } else {
                HighlightAnalysisAuthorization authorization = client.authorizeHighlightAnalysis(job.jobId(), machineIdentifier);
                client.failHighlightAnalysis(
                        job.jobId(),
                        machineIdentifier,
                        authorization.analysisId(),
                        java.util.UUID.fromString(String.valueOf(assetId)),
                        code,
                        message,
                        terminal);
            }
        } catch (Exception reportFailure) {
            System.err.println("Worker failed to report highlight analysis failure: " + reportFailure.getMessage());
        }
    }

    private static List<String> supportedJobTypes(boolean ffprobeAvailable, boolean ffmpegAvailable) {
        List<String> types = new ArrayList<>();
        types.add("SYSTEM_TEST");
        types.add("IMPORT_MEDIA");
        types.add("ANALYZE_HIGHLIGHTS");
        if (ffprobeAvailable) {
            types.add("INSPECT_MEDIA");
        }
        if (ffmpegAvailable) {
            types.add("CREATE_CLIP");
            types.add("CREATE_SOCIAL_VERTICAL");
        }
        return List.copyOf(types);
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
