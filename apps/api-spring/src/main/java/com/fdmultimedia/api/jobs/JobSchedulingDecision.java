package com.fdmultimedia.api.jobs;

import java.util.List;
import java.util.Optional;

record JobSchedulingDecision(Optional<Job> job, List<String> reasonCodes) {

    static JobSchedulingDecision none(String reasonCode) {
        return new JobSchedulingDecision(Optional.empty(), List.of(reasonCode));
    }

    static JobSchedulingDecision selected(Job job, List<String> reasonCodes) {
        return new JobSchedulingDecision(Optional.of(job), List.copyOf(reasonCodes));
    }
}
