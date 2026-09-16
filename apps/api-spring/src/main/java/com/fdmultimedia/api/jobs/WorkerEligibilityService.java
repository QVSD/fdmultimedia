package com.fdmultimedia.api.jobs;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WorkerEligibilityService {

    public WorkerEligibility eligibleCapabilities(WorkerJobClaimRequest request) {
        return new WorkerEligibility(
                supportedTypeNames(request),
                supportedHighlightAnalyzers(request));
    }

    private List<String> supportedTypeNames(WorkerJobClaimRequest request) {
        if (request.supportedJobTypes() == null || request.supportedJobTypes().isEmpty()) {
            return List.of(JobType.SYSTEM_TEST.name());
        }
        List<String> supported = request.supportedJobTypes().stream()
                .filter(type -> type != null)
                .map(Enum::name)
                .distinct()
                .toList();
        return supported.isEmpty() ? List.of(JobType.SYSTEM_TEST.name()) : supported;
    }

    private List<String> supportedHighlightAnalyzers(WorkerJobClaimRequest request) {
        if (request.supportedHighlightAnalyzers() == null || request.supportedHighlightAnalyzers().isEmpty()) {
            return List.of("DETERMINISTIC_V1");
        }
        List<String> supported = request.supportedHighlightAnalyzers().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return supported.isEmpty() ? List.of("DETERMINISTIC_V1") : supported;
    }
}
