package com.fdmultimedia.worker;

import java.util.Map;

final class GenerateCoordinatedSocialCopyExecutor {

    private final Map<String, CoordinatedCopyProvider> providers;

    GenerateCoordinatedSocialCopyExecutor(Map<String, CoordinatedCopyProvider> providers) {
        this.providers = Map.copyOf(providers);
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier) throws Exception {
        CoordinatedCopyAuthorization authorization = client.authorizeCoordinatedCopyGeneration(job.jobId(), machineIdentifier);
        CoordinatedCopyProvider provider = providers.get(authorization.provider());
        if (provider == null) {
            throw new ImportFailureException("AI_PROVIDER_UNAVAILABLE", "Worker does not support provider " + authorization.provider(), true);
        }
        CoordinatedCopyResult result = provider.generate(authorization);
        client.completeCoordinatedCopyGeneration(job.jobId(), machineIdentifier, authorization, result);
    }
}
