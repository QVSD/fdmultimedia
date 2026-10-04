package com.fdmultimedia.api.adaptivememory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Source-level guards for the Phase 17N boundary: deterministic governance memory that only filters, never ranks or mutates. */
class AdaptiveMemoryArchitectureTest {
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
        return read(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test
    void memoryHasNoRobotPolicyAuthorizationRollbackAnalyticsOrAiDependency() throws IOException {
        for (Path p : sources("adaptivememory")) {
            assertThat(code(p)).as(p.getFileName().toString())
                    .doesNotContain("RobotChangeProposalService").doesNotContain("RollbackRecommendationService")
                    .doesNotContain("RobotAdaptivePolicyService").doesNotContain("RobotAdaptivePolicyRepository")
                    .doesNotContain("AdaptiveExecutionService").doesNotContain("RobotAdaptiveExecutionAuthorization")
                    .doesNotContain("AutonomousProposalService").doesNotContain("OptimizationProposalService")
                    .doesNotContain("applyPersona").doesNotContain(".rollback(").doesNotContain("robots.save")
                    .doesNotContain("update robots").doesNotContain("update robot_adaptive_policies")
                    .doesNotContain("update robot_adaptive_execution_authorizations").doesNotContain("contentsuggestions")
                    .doesNotContain("Ollama").doesNotContain("AiProvider");
        }
    }

    @Test
    void memoryNeverReadsPublicationAnalyticsOrCalculatesPerformance() throws IOException {
        for (Path p : sources("adaptivememory")) {
            assertThat(code(p)).as(p.getFileName().toString()).doesNotContain("publication_analytics_snapshots")
                    .doesNotContain("from publications").doesNotContain("PublicationAnalytics").doesNotContain("PublicationDashboardStore")
                    .doesNotContain("percentile_cont").doesNotContain("avg(");
        }
    }

    @Test
    void memoryOnlyFiltersItHasNoScoreRankingWeightOrSortByQuality() throws IOException {
        String all = sources("adaptivememory").stream().map(AdaptiveMemoryArchitectureTest::code).collect(Collectors.joining("\n")).toLowerCase();
        assertThat(all).doesNotContain("leaderboard").doesNotContain("bestpersona").doesNotContain("winner").doesNotContain("blacklist")
                .doesNotContain("reward").doesNotContain("bandit").doesNotContain("score");
        // Screen decides one candidate at a time; it exposes no list sorting or ordering of candidates.
        String service = code(Path.of("src/main/java/com/fdmultimedia/api/adaptivememory/AdaptiveMemoryService.java"));
        assertThat(service.substring(service.indexOf("class Screen"), service.indexOf("public Screen screen"))).doesNotContain("sort")
                .doesNotContain("Comparator").doesNotContain("List<");
        // 17K iterates the canonical UUID-ordered candidate list; it must not re-sort it by memory
        String autonomous = code(Path.of("src/main/java/com/fdmultimedia/api/optimization/AutonomousProposalService.java"));
        assertThat(autonomous).doesNotContain("sort(").doesNotContain("Comparator").doesNotContain("sorted(");
    }

    @Test
    void controllerIsReadOnlyAndSecurityMapsMemoryToTheUserRole() throws IOException {
        String controller = code(ROOT.resolve("adaptivememory/AdaptiveMemoryController.java"));
        assertThat(controller).doesNotContain("@PostMapping").doesNotContain("@PutMapping").doesNotContain("@PatchMapping")
                .doesNotContain("@DeleteMapping").doesNotContain("@RequestBody");
        String security = read(ROOT.resolve("auth/security/SecurityConfig.java"));
        assertThat(security).contains("\"/api/adaptive-memory/**\").hasRole(\"USER\")").contains("\"/api/robots/**\").hasRole(\"USER\")");
    }

    @Test
    void onlyPhase17KDependsOnMemoryAndNoCyclesExist() throws IOException {
        for (String pkg : List.of("robotchanges", "safety", "analytics", "experiments", "robots", "campaigns", "personas")) {
            for (Path p : sources(pkg)) assertThat(read(p)).as(p.toString()).doesNotContain("com.fdmultimedia.api.adaptivememory");
        }
        for (Path p : sources("adaptivememory")) {
            assertThat(read(p)).as(p.getFileName().toString()).doesNotContain("com.fdmultimedia.api.optimization")
                    .doesNotContain("com.fdmultimedia.api.robotchanges").doesNotContain("com.fdmultimedia.api.safety");
        }
        assertThat(read(ROOT.resolve("optimization/AutonomousProposalService.java"))).contains("AdaptiveMemoryService");
        assertThat(read(ROOT.resolve("optimization/OptimizationProposalService.java"))).doesNotContain("adaptivememory");
    }

    @Test
    void thePureProjectorHasNoClockPersistenceOrFrameworkDependency() {
        String projector = code(ROOT.resolve("adaptivememory/AdaptiveMemoryProjector.java"));
        assertThat(projector).doesNotContain("Instant.now").doesNotContain("Clock").doesNotContain("Repository").doesNotContain("jdbc")
                .doesNotContain("org.springframework");
    }

    @Test
    void migrationNeverEditsEarlierOnesAndProjectsNoDataInSql() throws IOException {
        String migration = read(Path.of("src/main/resources/db/migration/V43__adaptive_transition_memory.sql")).toLowerCase();
        assertThat(migration).doesNotContain("insert into robot_adaptive_transition_memory").doesNotContain("alter table robots")
                .doesNotContain("update ");
    }
}
