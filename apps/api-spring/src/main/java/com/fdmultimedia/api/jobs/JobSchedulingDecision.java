package com.fdmultimedia.api.jobs;

import java.util.List;
import java.util.Optional;

record JobSchedulingDecision(
        Optional<Job> job,
        List<String> reasonCodes,
        Double suitabilityScore,
        boolean telemetryFresh,
        boolean fallbackUsed,
        boolean starvationOverride) {

    static JobSchedulingDecision none(String reasonCode) {
        return new JobSchedulingDecision(Optional.empty(), List.of(reasonCode), null, false, false, false);
    }

    static JobSchedulingDecision selected(Job job, List<String> reasonCodes, double score, boolean telemetryFresh) {
        List<String> codes = List.copyOf(reasonCodes);
        boolean fallback = codes.stream().anyMatch(code -> code.endsWith("_FIFO"));
        boolean starvation = codes.contains("STARVATION_PROTECTION");
        return new JobSchedulingDecision(Optional.of(job), codes, score, telemetryFresh, fallback, starvation);
    }
}
