package com.fdmultimedia.worker;

import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        Map<String, HighlightAnalyzer> highlightAnalyzers = new LinkedHashMap<>();
        highlightAnalyzers.put("DETERMINISTIC_V1", new DeterministicHighlightAnalyzer());
        // SEMANTIC_HIGHLIGHTS_V2: deterministic, transcript-driven, no LLM
        // dependency, so — unlike TRANSCRIPT_SEMANTIC_V1 below — always on.
        highlightAnalyzers.put("DETERMINISTIC_V2", new DeterministicMultimodalHighlightAnalyzer());
        highlightAnalyzers.put("DETERMINISTIC_V3", new TranscriptSemanticHighlightAnalyzer());
        HighlightAnalyzer semanticAnalyzer = semanticHighlightAnalyzer(config);
        boolean semanticHighlightAvailable = semanticAnalyzer != null;
        if (semanticHighlightAvailable) {
            highlightAnalyzers.put("TRANSCRIPT_SEMANTIC_V1", semanticAnalyzer);
            System.err.println("Semantic highlight provider available: " + config.semanticHighlightRuntime() + " model " + config.semanticHighlightModel());
        } else {
            System.err.println("Semantic highlight provider unavailable; TRANSCRIPT_SEMANTIC_V1 analyzer disabled");
        }
        AnalyzeHighlightsExecutor analyzeHighlightsExecutor = new AnalyzeHighlightsExecutor(highlightAnalyzers);
        Map<String, ContentEnrichmentProvider> contentEnrichmentProviders = new LinkedHashMap<>();
        contentEnrichmentProviders.put("DETERMINISTIC_TEST", new DeterministicSocialCopyProvider());
        OllamaContentEnrichmentProvider ollamaContentProvider = ollamaContentEnrichmentProvider(config);
        if (ollamaContentProvider != null) {
            contentEnrichmentProviders.put("OLLAMA", ollamaContentProvider);
            System.err.println("Content AI provider available: OLLAMA model " + config.contentAiModel() + " (plus always-on DETERMINISTIC_TEST)");
        } else {
            System.err.println("Content AI provider available: DETERMINISTIC_TEST only (OLLAMA not configured or unreachable)");
        }
        GenerateSocialCopyExecutor generateSocialCopyExecutor = new GenerateSocialCopyExecutor(contentEnrichmentProviders);
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
        TranscriptionProvider transcriptionProvider = transcriptionProvider(config);
        boolean transcriptionAvailable = ffmpegAvailable && transcriptionProvider.isAvailable();
        if (transcriptionAvailable) {
            System.err.println("Transcription provider available: " + transcriptionProvider.providerName() + " model " + transcriptionProvider.modelName());
        } else {
            System.err.println("Transcription provider unavailable; TRANSCRIBE_MEDIA capability disabled");
        }
        TranscribeMediaExecutor transcribeMediaExecutor = transcriptionAvailable
                ? new TranscribeMediaExecutor(config.ffmpegPath(), transcriptionProvider)
                : null;
        PublishingProvider publishingProvider = new TestPublishingProvider();
        boolean publishingAvailable = publishingProvider.isAvailable();
        if (publishingAvailable) {
            System.err.println("Publishing provider available: " + publishingProvider.platform() + " (non-real, deterministic)");
        } else {
            System.err.println("Publishing provider unavailable; PUBLISH_MEDIA capability disabled");
        }
        PublishMediaExecutor publishMediaExecutor = publishingAvailable ? new PublishMediaExecutor(publishingProvider) : null;
        List<String> supportedJobTypes = supportedJobTypes(ffprobeAvailable, ffmpegAvailable, transcriptionAvailable, publishingAvailable);
        List<String> supportedHighlightAnalyzers = supportedHighlightAnalyzers(semanticHighlightAvailable);
        List<String> supportedPublishingProviders = supportedPublishingProviders(config.instagramPublishingEnabled());
        if (config.instagramPublishingEnabled()) {
            System.err.println("Instagram publishing driving enabled on this worker (no credential is ever held here)");
        }
        AtomicInteger activeJobs = new AtomicInteger(0);
        WorkerTelemetryCollector telemetryCollector = new WorkerTelemetryCollector();

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
        executor.submit(() -> heartbeatLoop(
                client,
                machineIdentifier,
                config,
                telemetryCollector,
                activeJobs,
                supportedJobTypes,
                supportedHighlightAnalyzers));
        executor.submit(() -> jobLoop(client, machineIdentifier, config, systemTestExecutor, importMediaExecutor, inspectMediaExecutor, clipExecutor, analyzeHighlightsExecutor, transcribeMediaExecutor, publishMediaExecutor, generateSocialCopyExecutor, supportedJobTypes, supportedHighlightAnalyzers, supportedPublishingProviders, activeJobs));
        shutdown.await();
    }

    private static void heartbeatLoop(
            WorkerAgentClient client,
            String machineIdentifier,
            WorkerAgentConfig config,
            WorkerTelemetryCollector telemetryCollector,
            AtomicInteger activeJobs,
            List<String> supportedJobTypes,
            List<String> supportedHighlightAnalyzers) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WorkerTelemetry telemetry = null;
                try {
                    telemetry = telemetryCollector.collect(activeJobs);
                } catch (Exception ex) {
                    System.err.println("Worker telemetry collection failed: " + ex.getMessage());
                }
                client.heartbeat(machineIdentifier, telemetry, supportedJobTypes, supportedHighlightAnalyzers, config.maxActiveJobs());
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
            TranscribeMediaExecutor transcribeMediaExecutor,
            PublishMediaExecutor publishMediaExecutor,
            GenerateSocialCopyExecutor generateSocialCopyExecutor,
            List<String> supportedJobTypes,
            List<String> supportedHighlightAnalyzers,
            List<String> supportedPublishingProviders,
            AtomicInteger activeJobs) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (activeJobs.get() >= config.maxActiveJobs()) {
                    sleep(config.jobPollInterval());
                    continue;
                }
                ClaimedJob job = client.claim(machineIdentifier, supportedJobTypes, supportedHighlightAnalyzers, supportedPublishingProviders);
                if (!job.available()) {
                    sleep(config.jobPollInterval());
                    continue;
                }
                activeJobs.incrementAndGet();
                try {
                    executeClaimedJob(client, machineIdentifier, config.workerName(), systemTestExecutor, importMediaExecutor, inspectMediaExecutor, clipExecutor, analyzeHighlightsExecutor, transcribeMediaExecutor, publishMediaExecutor, generateSocialCopyExecutor, job);
                } finally {
                    activeJobs.decrementAndGet();
                }
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
            TranscribeMediaExecutor transcribeMediaExecutor,
            PublishMediaExecutor publishMediaExecutor,
            GenerateSocialCopyExecutor generateSocialCopyExecutor,
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
            } else if ("TRANSCRIBE_MEDIA".equals(job.type()) && transcribeMediaExecutor != null) {
                executeTranscribeMediaJob(client, machineIdentifier, transcribeMediaExecutor, job);
            } else if ("PUBLISH_MEDIA".equals(job.type()) && publishMediaExecutor != null) {
                executePublishMediaJob(client, machineIdentifier, publishMediaExecutor, job);
            } else if ("GENERATE_SOCIAL_COPY".equals(job.type())) {
                executeGenerateSocialCopyJob(client, machineIdentifier, generateSocialCopyExecutor, job);
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

    private static void executeGenerateSocialCopyJob(
            WorkerAgentClient client,
            String machineIdentifier,
            GenerateSocialCopyExecutor generateSocialCopyExecutor,
            ClaimedJob job) throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread renewer = new Thread(() -> leaseRenewLoop(client, machineIdentifier, job, running), "fdm-job-lease-renewer");
        renewer.setDaemon(true);
        renewer.start();
        try {
            generateSocialCopyExecutor.execute(client, job, machineIdentifier);
        } catch (ImportFailureException ex) {
            reportSocialCopyFailure(client, machineIdentifier, job, ex.code(), ex.getMessage(), ex.terminal());
        } catch (Exception ex) {
            reportSocialCopyFailure(client, machineIdentifier, job, "GENERATE_SOCIAL_COPY_FAILED", ex.getMessage(), false);
        } finally {
            running.set(false);
            renewer.interrupt();
        }
    }

    private static void executeTranscribeMediaJob(
            WorkerAgentClient client,
            String machineIdentifier,
            TranscribeMediaExecutor transcribeMediaExecutor,
            ClaimedJob job) throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread renewer = new Thread(() -> leaseRenewLoop(client, machineIdentifier, job, running), "fdm-job-lease-renewer");
        renewer.setDaemon(true);
        renewer.start();
        try {
            transcribeMediaExecutor.execute(client, job, machineIdentifier);
        } catch (ImportFailureException ex) {
            reportTranscriptionFailure(client, machineIdentifier, job, ex.code(), ex.getMessage(), ex.terminal());
        } catch (Exception ex) {
            reportTranscriptionFailure(client, machineIdentifier, job, "TRANSCRIBE_MEDIA_FAILED", ex.getMessage(), false);
        } finally {
            running.set(false);
            renewer.interrupt();
        }
    }

    private static void executePublishMediaJob(
            WorkerAgentClient client,
            String machineIdentifier,
            PublishMediaExecutor publishMediaExecutor,
            ClaimedJob job) throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread renewer = new Thread(() -> leaseRenewLoop(client, machineIdentifier, job, running), "fdm-job-lease-renewer");
        renewer.setDaemon(true);
        renewer.start();
        try {
            publishMediaExecutor.execute(client, job, machineIdentifier);
        } catch (ImportFailureException ex) {
            reportPublishingFailure(client, machineIdentifier, job, ex.code(), ex.getMessage(), ex.terminal());
        } catch (Exception ex) {
            reportPublishingFailure(client, machineIdentifier, job, "PUBLISH_MEDIA_FAILED", ex.getMessage(), false);
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

    private static void reportSocialCopyFailure(
            WorkerAgentClient client,
            String machineIdentifier,
            ClaimedJob job,
            String code,
            String message,
            boolean terminal) {
        try {
            SocialCopyAuthorization authorization = client.authorizeSocialCopyGeneration(job.jobId(), machineIdentifier);
            client.failSocialCopyGeneration(job.jobId(), machineIdentifier, authorization.suggestionId(), code, message, terminal);
        } catch (Exception reportFailure) {
            System.err.println("Worker failed to report content suggestion failure: " + reportFailure.getMessage());
        }
    }

    private static void reportPublishingFailure(
            WorkerAgentClient client,
            String machineIdentifier,
            ClaimedJob job,
            String code,
            String message,
            boolean terminal) {
        Object publicationId = job.payload().get("publicationId");
        Object assetId = job.payload().get("assetId");
        Object socialAccountId = job.payload().get("socialAccountId");
        try {
            if (publicationId == null || assetId == null || socialAccountId == null) {
                client.fail(job.jobId(), machineIdentifier, code, message, terminal);
            } else {
                client.failPublication(
                        job.jobId(),
                        machineIdentifier,
                        java.util.UUID.fromString(String.valueOf(publicationId)),
                        java.util.UUID.fromString(String.valueOf(assetId)),
                        java.util.UUID.fromString(String.valueOf(socialAccountId)),
                        code,
                        message,
                        terminal);
            }
        } catch (Exception reportFailure) {
            System.err.println("Worker failed to report publishing failure: " + reportFailure.getMessage());
        }
    }

    private static void reportTranscriptionFailure(
            WorkerAgentClient client,
            String machineIdentifier,
            ClaimedJob job,
            String code,
            String message,
            boolean terminal) {
        try {
            TranscriptionAuthorization authorization = client.authorizeTranscription(job.jobId(), machineIdentifier);
            client.failTranscription(
                    job.jobId(),
                    machineIdentifier,
                    authorization.transcriptId(),
                    authorization.assetId(),
                    code,
                    message,
                    terminal);
        } catch (Exception reportFailure) {
            System.err.println("Worker failed to report transcription failure: " + reportFailure.getMessage());
        }
    }

    private static List<String> supportedJobTypes(
            boolean ffprobeAvailable, boolean ffmpegAvailable, boolean transcriptionAvailable, boolean publishingAvailable) {
        List<String> types = new ArrayList<>();
        types.add("SYSTEM_TEST");
        types.add("IMPORT_MEDIA");
        types.add("ANALYZE_HIGHLIGHTS");
        // DETERMINISTIC_TEST content-enrichment needs no external service and
        // is always available, exactly like DETERMINISTIC_V1 highlight analysis.
        types.add("GENERATE_SOCIAL_COPY");
        if (ffprobeAvailable) {
            types.add("INSPECT_MEDIA");
        }
        if (ffmpegAvailable) {
            types.add("CREATE_CLIP");
            types.add("CREATE_SOCIAL_VERTICAL");
        }
        if (transcriptionAvailable) {
            types.add("TRANSCRIBE_MEDIA");
        }
        if (publishingAvailable) {
            types.add("PUBLISH_MEDIA");
        }
        return List.copyOf(types);
    }

    static List<String> supportedHighlightAnalyzers(boolean semanticHighlightAvailable) {
        List<String> analyzers = new ArrayList<>();
        analyzers.add("DETERMINISTIC_V1");
        analyzers.add("DETERMINISTIC_V2");
        analyzers.add("DETERMINISTIC_V3");
        if (semanticHighlightAvailable) {
            analyzers.add("TRANSCRIPT_SEMANTIC_V1");
        }
        return List.copyOf(analyzers);
    }

    private static List<String> supportedPublishingProviders(boolean instagramPublishingEnabled) {
        List<String> providers = new ArrayList<>();
        providers.add("TEST");
        if (instagramPublishingEnabled) {
            providers.add("INSTAGRAM");
        }
        return List.copyOf(providers);
    }

    private static TranscriptionProvider transcriptionProvider(WorkerAgentConfig config) {
        if ("WHISPER_CPP".equalsIgnoreCase(config.transcriptionRuntime())) {
            return new WhisperCppCliProvider(
                    config.transcriptionCommand(),
                    config.transcriptionModel(),
                    config.transcriptionTimeout());
        }
        return new LocalWhisperCliProvider(
                config.transcriptionCommand(),
                config.transcriptionModel(),
                config.transcriptionTimeout());
    }

    private static HighlightAnalyzer semanticHighlightAnalyzer(WorkerAgentConfig config) {
        if (!"OLLAMA".equalsIgnoreCase(config.semanticHighlightRuntime())) {
            return null;
        }
        OllamaSemanticHighlightAnalyzer analyzer = new OllamaSemanticHighlightAnalyzer(
                config.semanticHighlightEndpoint(),
                config.semanticHighlightModel(),
                config.semanticHighlightTimeout());
        return analyzer.isAvailable() ? analyzer : null;
    }

    private static OllamaContentEnrichmentProvider ollamaContentEnrichmentProvider(WorkerAgentConfig config) {
        if (!"OLLAMA".equalsIgnoreCase(config.contentAiRuntime())) {
            return null;
        }
        OllamaContentEnrichmentProvider provider = new OllamaContentEnrichmentProvider(
                config.contentAiEndpoint(),
                config.contentAiModel(),
                config.contentAiTimeout());
        return provider.isAvailable() ? provider : null;
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
