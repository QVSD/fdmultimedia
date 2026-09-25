package com.fdmultimedia.worker;

import java.util.Map;

final class GenerateCampaignPlanExecutor {

    private final Map<String, CampaignPlanProvider> providers;

    GenerateCampaignPlanExecutor(Map<String, CampaignPlanProvider> providers) {
        this.providers = Map.copyOf(providers);
    }

    void execute(WorkerAgentClient client, ClaimedJob job, String machineIdentifier) throws Exception {
        CampaignPlanAuthorization authorization = client.authorizeCampaignPlanGeneration(job.jobId(), machineIdentifier);
        CampaignPlanProvider provider = providers.get(authorization.provider());
        if (provider == null) {
            throw new ImportFailureException("AI_PROVIDER_UNAVAILABLE", "Worker does not support provider " + authorization.provider(), true);
        }
        CampaignPlanResult result = provider.generate(authorization);
        client.completeCampaignPlanGeneration(job.jobId(), machineIdentifier, authorization, result);
    }
}
