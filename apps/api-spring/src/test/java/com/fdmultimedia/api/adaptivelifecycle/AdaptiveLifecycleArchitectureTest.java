package com.fdmultimedia.api.adaptivelifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AdaptiveLifecycleArchitectureTest {
    private static final Path ROOT = Path.of("src/main/java/com/fdmultimedia/api/adaptivelifecycle");
    private static String read(Path path) { try { return Files.readString(path); } catch (IOException e) { throw new IllegalStateException(e); } }
    private static String code(Path path) { return read(path).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", ""); }

    @Test void controllerIsReadOnlyAndHasNoMutationRoute() {
        String controller = code(ROOT.resolve("AdaptiveLifecycleController.java"));
        assertThat(controller).contains("@GetMapping").doesNotContain("@PostMapping").doesNotContain("@PutMapping")
                .doesNotContain("@PatchMapping").doesNotContain("@DeleteMapping").doesNotContain("@RequestBody");
    }

    @Test void lifecyclePackageCannotApplyRollbackAuthorizeOrPersistBusinessFacts() throws IOException {
        try (Stream<Path> paths = Files.walk(ROOT)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = code(path);
                assertThat(source).as(path.getFileName().toString()).doesNotContain("applyCore(")
                        .doesNotContain(".rollback(").doesNotContain(".approve(").doesNotContain(".revoke(")
                        .doesNotContain("saveAndFlush(").doesNotContain("ApplicationEventPublisher")
                        .doesNotContain("AdaptiveExecutionExecutor").doesNotContain("RollbackRecommendationService");
            }
        }
    }

    @Test void lifecycleHasNoAnalyticsStatisticsMemoryProjectionOrAiEngine() throws IOException {
        try (Stream<Path> paths = Files.walk(ROOT)) {
            String all = paths.filter(p -> p.toString().endsWith(".java")).map(AdaptiveLifecycleArchitectureTest::code)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(all).doesNotContain("PublicationAnalytics").doesNotContain("percentile_cont")
                    .doesNotContain("AdaptiveMemoryProjector").doesNotContain("AdaptiveMemoryProjectionService")
                    .doesNotContain("PostChangeSafetyEvaluator").doesNotContain("Ollama").doesNotContain("AiProvider");
        }
    }

    @Test void pureEngineHasNoFrameworkClockOrRepositoryDependency() {
        String engine = code(ROOT.resolve("AdaptiveLifecycleEngine.java"));
        assertThat(engine).doesNotContain("org.springframework").doesNotContain("Clock").doesNotContain("Instant.now")
                .doesNotContain("Repository").doesNotContain("Service");
    }

    @Test void noMigrationPersistsTheReconstructableReadModel() throws IOException {
        try (Stream<Path> migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path migration : migrations.filter(p -> p.toString().endsWith(".sql")).toList()) {
                String sql = read(migration).toLowerCase();
                assertThat(sql).as(migration.getFileName().toString())
                        .doesNotContain("adaptive_lifecycle")
                        .doesNotContain("robot_adaptive_lifecycle");
            }
        }
    }
}
