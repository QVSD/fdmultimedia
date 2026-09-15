package com.fdmultimedia.worker;

import java.util.Map;

final class AnalyzeHighlightsExecutor {

    private final Map<String, HighlightAnalyzer> analyzers;

    AnalyzeHighlightsExecutor(Map<String, HighlightAnalyzer> analyzers) {
        this.analyzers = Map.copyOf(analyzers);
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier) throws Exception {
        HighlightAnalysisAuthorization authorization = client.authorizeHighlightAnalysis(job.jobId(), machineIdentifier);
        HighlightAnalyzer analyzer = analyzers.get(authorization.analyzerType());
        if (analyzer == null) {
            throw new ImportFailureException("UNSUPPORTED_ANALYZER", "Worker does not support " + authorization.analyzerType(), true);
        }
        HighlightAnalysisResult result = analyzer.analyze(authorization);
        client.completeHighlightAnalysis(job.jobId(), machineIdentifier, authorization, result);
    }
}
