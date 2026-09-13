package com.fdmultimedia.worker;

final class AnalyzeHighlightsExecutor {

    private final HighlightAnalyzer analyzer;

    AnalyzeHighlightsExecutor(HighlightAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier) throws Exception {
        HighlightAnalysisAuthorization authorization = client.authorizeHighlightAnalysis(job.jobId(), machineIdentifier);
        HighlightAnalysisResult result = analyzer.analyze(authorization);
        client.completeHighlightAnalysis(job.jobId(), machineIdentifier, authorization, result);
    }
}
