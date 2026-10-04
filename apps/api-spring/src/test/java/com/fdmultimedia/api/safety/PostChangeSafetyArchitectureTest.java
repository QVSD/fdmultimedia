package com.fdmultimedia.api.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Source-level guards for the Phase 17M safety boundary: observe, evaluate, persist, recommend, never execute. */
class PostChangeSafetyArchitectureTest {
    private static final Path ROOT = Path.of("src/main/java/com/fdmultimedia/api");

    private static List<Path> sources(String pkg) throws IOException {
        try (Stream<Path> s = Files.walk(ROOT.resolve(pkg))) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static String read(Path p) {
        try { return Files.readString(p); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static String code(Path p) {
        // strip comments so documentation wording cannot satisfy or violate a structural rule
        return read(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test
    void safetyPackageHasNoRobotPolicyAuthorizationOrAiMutationDependency() throws IOException {
        for (Path p : sources("safety")) {
            String src = code(p);
            assertThat(src).as(p.getFileName().toString()).doesNotContain("RobotAdaptivePolicyService")
                    .doesNotContain("RobotAdaptivePolicyRepository").doesNotContain("RobotAdaptivePolicyModels")
                    .doesNotContain("RobotAdaptiveExecutionAuthorization").doesNotContain("AdaptiveExecutionService")
                    .doesNotContain("AdaptiveExecutionExecutor").doesNotContain("AdaptiveGuardrailService")
                    .doesNotContain("robot_adaptive_policies").doesNotContain("robot_adaptive_execution_authorizations")
                    .doesNotContain("applyPersona").doesNotContain("robots.save").doesNotContain("OptimizationProposalService")
                    .doesNotContain("AutonomousProposalService").doesNotContain("contentsuggestions").doesNotContain("Ollama")
                    .doesNotContain("AiProvider");
        }
    }

    @Test
    void onlyTheHumanRecommendationServiceReferencesTheCanonicalRollback() throws IOException {
        for (Path p : sources("safety")) {
            String name = p.getFileName().toString();
            if (name.equals("RollbackRecommendationService.java")) continue;
            assertThat(code(p)).as(name).doesNotContain("RobotChangeProposalService");
            // the HTTP controller may only forward the explicit human request to the recommendation service
            if (!name.equals("PostChangeSafetyController.java")) assertThat(code(p)).as(name).doesNotContain(".rollback(");
        }
        String service = code(ROOT.resolve("safety/RollbackRecommendationService.java"));
        assertThat(service.split("canonicalRollback\\.rollback\\(", -1).length - 1).as("single delegation call").isEqualTo(1);
    }

    @Test
    void noSchedulerOrListenerCanReachRollbackOrTheHumanActions() throws IOException {
        for (Path p : sources("safety")) {
            String src = code(p);
            if (src.contains("@Scheduled") || src.contains("@EventListener") || src.contains("@TransactionalEventListener")) {
                assertThat(src).as(p.getFileName().toString()).doesNotContain("RollbackRecommendationService")
                        .doesNotContain("RobotChangeProposalService").doesNotContain("rollback(").doesNotContain("acknowledge(")
                        .doesNotContain("dismiss(");
            }
        }
        // and nothing else in the code base schedules a call into the recommendation actions
        for (String pkg : List.of("robotchanges", "optimization", "analytics", "robots", "experiments")) {
            for (Path p : sources(pkg)) assertThat(read(p)).as(p.toString()).doesNotContain("RollbackRecommendationService");
        }
    }

    @Test
    void earlierPhasesDoNotDependOnPhase17M() throws IOException {
        for (String pkg : List.of("robotchanges", "optimization", "analytics", "experiments", "robots", "campaigns", "personas")) {
            for (Path p : sources(pkg)) assertThat(read(p)).as(p.toString()).doesNotContain("com.fdmultimedia.api.safety");
        }
    }

    @Test
    void safetyEvaluationHasNoClockOrPersistenceInThePureEvaluator() {
        String evaluator = code(ROOT.resolve("safety/PostChangeSafetyEvaluator.java"));
        assertThat(evaluator).doesNotContain("Instant.now").doesNotContain("Clock").doesNotContain("Repository").doesNotContain("jdbc");
    }

    @Test
    void mutationEndpointsRequireAHumanUserRoleAndWorkersAreRejected() throws IOException {
        String security = read(ROOT.resolve("auth/security/SecurityConfig.java"));
        assertThat(security).contains("\"/api/rollback-recommendations/**\").hasRole(\"USER\")");
        assertThat(security).contains("\"/api/robots/**\").hasRole(\"USER\")");
    }

    @Test
    void postChangeSafetyModelsAreNonCausalAndNeutral() throws IOException {
        assertThat(PostChangeSafetyModels.NON_CAUSAL_DISCLAIMER)
                .isEqualTo("Observed post-change difference is not proof that the configuration change caused the outcome.");
        String all = sources("safety").stream().map(PostChangeSafetyArchitectureTest::code).collect(Collectors.joining("\n")).toLowerCase();
        assertThat(all).doesNotContain("bad persona").doesNotContain("ai detected");
    }
}
