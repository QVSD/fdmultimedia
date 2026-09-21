package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExperimentDecisionReadinessService {
    public static final String VERSION = "EXPERIMENT_GUARDRAILS_V1";
    private final AuthService auth;
    private final ExperimentRepository experiments;
    private final ExperimentAnalysisService analysis;
    private final ExperimentDecisionProperties config;
    private final ExperimentAnalysisProperties analysisConfig;

    public ExperimentDecisionReadinessService(AuthService auth, ExperimentRepository experiments,
            ExperimentAnalysisService analysis, ExperimentDecisionProperties config, ExperimentAnalysisProperties analysisConfig) {
        this.auth = auth;
        this.experiments = experiments;
        this.analysis = analysis;
        this.config = config;
        this.analysisConfig = analysisConfig;
    }

    @Transactional(readOnly = true)
    public ExperimentDecisionReadiness read(AuthenticatedUser user, UUID id) {
        var workspace = auth.currentMembershipFor(user).getWorkspace();
        Experiment experiment = experiments.findByWorkspaceAndId(workspace, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found"));
        ExperimentAnalysisResponse evidence = analysis.analyze(user, id);
        return new ExperimentDecisionReadiness(VERSION, id, experiment.getStatus(), evidence.analysisVersion(),
                evidence.primaryMetric(), evidence.targetObservationWindow(), experiment.getMinimumPracticalEffect(),
                evaluate(experiment, evidence.assignedObserved()), evaluate(experiment, evidence.perProtocolObserved()),
                "Meeting the practical-effect threshold does not imply statistical certainty.",
                "An interval excluding zero does not imply the effect meets the pre-specified practical threshold.");
    }

    private ExperimentDecisionReadiness.Population evaluate(Experiment experiment, ExperimentPopulationAnalysis population) {
        List<ExperimentDecisionReadiness.Check> checks = new ArrayList<>();
        var a = population.variantA();
        var b = population.variantB();
        BigDecimal threshold = experiment.getMinimumPracticalEffect();
        check(checks, "DEFINITION", experiment.getStatus() == ExperimentStatus.DRAFT ? "BLOCKED" : "PASS",
                "Experiment definition must be activated and frozen.", null, null);
        check(checks, "PROVIDER_CONSISTENCY", population.status() == AnalysisStatus.MIXED_PROVIDERS ? "BLOCKED"
                : a.metricSampleCount() + b.metricSampleCount() == 0 ? "NOT_APPLICABLE" : "PASS",
                "Observed outcomes must use a consistent analytics provider.", null, null);
        boolean enoughSample = a.metricSampleCount() >= analysisConfig.getMinSamplePerVariant()
                && b.metricSampleCount() >= analysisConfig.getMinSamplePerVariant();
        check(checks, "MIN_SAMPLE", enoughSample ? "PASS" : "BLOCKED",
                "Both variants need the configured observed sample.", BigDecimal.valueOf(Math.min(a.metricSampleCount(), b.metricSampleCount())),
                BigDecimal.valueOf(analysisConfig.getMinSamplePerVariant()));
        check(checks, "OBSERVATION_MATURITY", a.assignmentCount() + b.assignmentCount() == 0 ? "NOT_APPLICABLE"
                : a.tooYoungCount() + b.tooYoungCount() == 0 ? "PASS"
                : !enoughSample ? "BLOCKED" : "WARN",
                "Some assignments have not yet reached the observation window.", BigDecimal.valueOf(a.tooYoungCount() + b.tooYoungCount()), null);
        coverage(checks, "ASSIGNMENT_OUTCOME_COVERAGE_A", a);
        coverage(checks, "ASSIGNMENT_OUTCOME_COVERAGE_B", b);
        BigDecimal gap = a.assignmentOutcomeCoverage() == null || b.assignmentOutcomeCoverage() == null ? null
                : a.assignmentOutcomeCoverage().subtract(b.assignmentOutcomeCoverage()).abs().multiply(BigDecimal.valueOf(100));
        check(checks, "ATTRITION_IMBALANCE", gap == null ? "NOT_APPLICABLE"
                : gap.compareTo(config.getAttritionWarningPercentagePoints()) >= 0 ? "WARN" : "PASS",
                "Difference in assignment-outcome coverage between variants (percentage points).", gap, config.getAttritionWarningPercentagePoints());
        deviation(checks, "PROTOCOL_DEVIATION_A", a);
        deviation(checks, "PROTOCOL_DEVIATION_B", b);
        check(checks, "ANALYSIS_STATUS", population.status() == AnalysisStatus.READY ? "PASS" : "BLOCKED",
                "Inferential analysis status: " + population.status(), null, null);
        check(checks, "PRACTICAL_EFFECT_CONFIGURED", threshold == null ? "WARN" : "PASS",
                threshold == null ? "Practical threshold was not configured for this legacy experiment." : "Practical threshold was frozen at activation.", null, threshold);
        BigDecimal difference = population.effect().absoluteMeanDifference();
        String practical = threshold == null ? "NOT_CONFIGURED" : difference == null ? "UNAVAILABLE"
                : difference.abs().compareTo(threshold) >= 0 ? "MEETS_OR_EXCEEDS_THRESHOLD" : "BELOW_THRESHOLD";
        check(checks, "PRACTICAL_EFFECT_RELATIONSHIP", threshold == null || difference == null ? "NOT_APPLICABLE"
                : "PASS", "The absolute observed mean difference is compared with the pre-specified threshold.",
                difference == null ? null : difference.abs(), threshold);
        String direction = difference == null ? "UNAVAILABLE" : difference.signum() > 0 ? "A_HIGHER_OBSERVED"
                : difference.signum() < 0 ? "B_HIGHER_OBSERVED" : "NO_OBSERVED_DIFFERENCE";
        var effect = population.effect();
        String region = threshold == null || effect.confidenceIntervalLower() == null || effect.confidenceIntervalUpper() == null
                ? "UNAVAILABLE" : effect.confidenceIntervalLower().compareTo(threshold) > 0 ? "ENTIRELY_ABOVE_POSITIVE_THRESHOLD"
                : effect.confidenceIntervalUpper().compareTo(threshold.negate()) < 0 ? "ENTIRELY_BELOW_NEGATIVE_THRESHOLD"
                : "OVERLAPS_PRACTICAL_REGION";
        boolean blocked = checks.stream().anyMatch(c -> c.status().equals("BLOCKED"));
        List<String> next = new ArrayList<>();
        if (a.tooYoungCount() + b.tooYoungCount() > 0) next.add("WAIT_FOR_MORE_MATURE_OUTCOMES");
        if (gap != null && gap.compareTo(config.getAttritionWarningPercentagePoints()) >= 0) next.add("REVIEW_ATTRITION");
        if (a.protocolDeviationCount() + b.protocolDeviationCount() > 0) next.add("REVIEW_PROTOCOL_DEVIATIONS");
        next.add("REVIEW_STATISTICAL_EVIDENCE");
        next.add("RECORD_HUMAN_DECISION");
        return new ExperimentDecisionReadiness.Population(population.population(), blocked ? "NOT_READY" : "READY_FOR_REVIEW",
                List.copyOf(checks), direction, practical, region, List.copyOf(next), population);
    }

    private void coverage(List<ExperimentDecisionReadiness.Check> checks, String code, ExperimentVariantAnalysis variant) {
        BigDecimal value = variant.assignmentOutcomeCoverage();
        check(checks, code, value == null || value.compareTo(config.getMinAssignmentOutcomeCoverage()) < 0 ? "BLOCKED" : "PASS",
                "Observed outcomes divided by assignments for Variant " + variant.variantKey() + ".",
                value, config.getMinAssignmentOutcomeCoverage());
    }

    private void deviation(List<ExperimentDecisionReadiness.Check> checks, String code, ExperimentVariantAnalysis variant) {
        BigDecimal rate = variant.assignmentCount() == 0 ? null : BigDecimal.valueOf(variant.protocolDeviationCount())
                .divide(BigDecimal.valueOf(variant.assignmentCount()), 6, RoundingMode.HALF_UP);
        check(checks, code, rate == null ? "NOT_APPLICABLE" : rate.compareTo(config.getProtocolDeviationWarningRate()) >= 0 ? "WARN" : "PASS",
                "Protocol deviation rate for Variant " + variant.variantKey() + ".", rate, config.getProtocolDeviationWarningRate());
    }

    private void check(List<ExperimentDecisionReadiness.Check> checks, String code, String status, String message,
            BigDecimal actual, BigDecimal threshold) {
        checks.add(new ExperimentDecisionReadiness.Check(code, status, message, actual, threshold));
    }
}
