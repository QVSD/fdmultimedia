package com.fdmultimedia.worker;

import java.util.Map;

final class GenerateSocialCopyExecutor {

    private final Map<String, ContentEnrichmentProvider> providers;

    GenerateSocialCopyExecutor(Map<String, ContentEnrichmentProvider> providers) {
        this.providers = Map.copyOf(providers);
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier) throws Exception {
        SocialCopyAuthorization authorization = client.authorizeSocialCopyGeneration(job.jobId(), machineIdentifier);
        ContentEnrichmentProvider provider = providers.get(authorization.provider());
        if (provider == null) {
            throw new ImportFailureException("AI_PROVIDER_UNAVAILABLE", "Worker does not support provider " + authorization.provider(), true);
        }
        SocialCopyResult result = provider.generate(authorization);
        client.completeSocialCopyGeneration(job.jobId(), machineIdentifier, authorization, result);
    }
}
