package com.fdmultimedia.api.jobs;

import java.util.List;

public record WorkerEligibility(
        List<String> supportedJobTypes,
        List<String> supportedHighlightAnalyzers) {
}
