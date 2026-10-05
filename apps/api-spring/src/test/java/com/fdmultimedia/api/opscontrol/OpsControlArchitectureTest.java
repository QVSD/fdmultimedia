package com.fdmultimedia.api.opscontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Source-level guards for the Phase 17Q boundary: a bounded, read-mostly, operator-only application view. */
class OpsControlArchitectureTest {
    private static final Path ROOT = Path.of("src/main/java/com/fdmultimedia/api");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(ROOT.resolve("opscontrol"))) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static String code(Path p) {
        try {
            return Files.readString(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @Test
    void noShellSqlConsoleContainerOrFilesystemAccess() throws IOException {
        for (Path p : sources()) {
            assertThat(code(p)).as(p.getFileName().toString()).doesNotContain("ProcessBuilder").doesNotContain("Runtime.getRuntime")
                    .doesNotContain("System.getenv").doesNotContain("System.getProperties").doesNotContain("java.nio.file")
                    .doesNotContain("java.io.File").doesNotContain("docker").doesNotContain("EnvironmentEndpoint");
        }
    }

    @Test
    void controllerHasNoMutationBesidesIncidentAcknowledgement() throws IOException {
        String controller = code(ROOT.resolve("opscontrol/OperationsControlController.java"));
        assertThat(controller).doesNotContain("@DeleteMapping").doesNotContain("@PutMapping").doesNotContain("@PatchMapping");
        assertThat(controller.split("@PostMapping", -1).length - 1).isEqualTo(1);
        assertThat(controller).contains("@PostMapping(\"/incidents/{id}/acknowledge\")");
    }

    @Test
    void everyControllerEndpointRequiresAnOperator() throws IOException {
        String controller = code(ROOT.resolve("opscontrol/OperationsControlController.java"));
        long endpoints = controller.split("@(Get|Post)Mapping", -1).length - 1;
        long guarded = controller.split("operator\\(authentication\\)", -1).length - 1;
        assertThat(endpoints).isEqualTo(8);
        assertThat(guarded).isGreaterThanOrEqualTo(endpoints);
    }

    @Test
    void controlPlaneDoesNotMutateBusinessStateOrRetryAnything() throws IOException {
        for (Path p : sources()) {
            String c = code(p).toLowerCase();
            assertThat(c).as(p.getFileName().toString())
                    .doesNotContain("update jobs").doesNotContain("update publications").doesNotContain("update publish_schedules")
                    .doesNotContain("update robots").doesNotContain("delete from jobs").doesNotContain("delete from publications")
                    .doesNotContain("insert into jobs").doesNotContain("insert into publications")
                    .doesNotContain("requeue").doesNotContain("retrypublication").doesNotContain("publicationservice");
        }
    }

    @Test
    void controlPlaneNeverReadsPayloadsCaptionsTokensOrPersonaDefinitions() throws IOException {
        for (Path p : sources()) {
            String c = code(p).toLowerCase();
            assertThat(c).as(p.getFileName().toString()).doesNotContain("payload").doesNotContain("caption").doesNotContain("transcript")
                    .doesNotContain("social_account_credentials").doesNotContain("provider_upload_url").doesNotContain("password_hash")
                    .doesNotContain("persona_definition").doesNotContain("presign").doesNotContain("publication_analytics_snapshots")
                    .doesNotContain("ai_provider").doesNotContain("ollama").doesNotContain("openai");
        }
    }

    @Test
    void sqlOnlyLivesInTheJdbcStore() throws IOException {
        for (Path p : sources()) {
            if (p.getFileName().toString().equals("JdbcOpsStore.java")) continue;
            assertThat(code(p)).as(p.getFileName().toString()).doesNotContain("JdbcTemplate").doesNotContain("EntityManager")
                    .doesNotContain("select ").doesNotContain("insert into");
        }
    }

    @Test
    void thereIsNoCallToAnExternalMonitoringOrNotificationSystem() throws IOException {
        for (Path p : sources()) {
            String c = code(p).toLowerCase();
            assertThat(c).as(p.getFileName().toString()).doesNotContain("pagerduty").doesNotContain("slack").doesNotContain("prometheus")
                    .doesNotContain("javamail").doesNotContain("resttemplate").doesNotContain("webclient").doesNotContain("httpclient");
        }
    }

    @Test
    void thresholdsAreBoundedConfigurationNotHardCodedInRules() throws IOException {
        String props = code(ROOT.resolve("opscontrol/OpsProperties.java"));
        assertThat(props).contains("backlogWarning").contains("backlogCritical").contains("probeTimeout").contains("bounded(");
        assertThat(new OpsProperties().getProbeTimeout().toMillis()).isBetween(250L, 1000L);
        assertThat(new OpsProperties().getProbeCacheTtl().toSeconds()).isBetween(5L, 15L);
        assertThat(new OpsProperties().getBacklogWarning().toMinutes()).isEqualTo(5);
        assertThat(new OpsProperties().getBacklogCritical().toMinutes()).isEqualTo(30);
    }

    @Test
    void propertiesAreClampedToSafeBounds() {
        OpsProperties p = new OpsProperties();
        p.setProbeTimeout(java.time.Duration.ofSeconds(60));
        assertThat(p.getProbeTimeout().toSeconds()).isLessThanOrEqualTo(5);
        p.setProbeTimeout(java.time.Duration.ofMillis(1));
        assertThat(p.getProbeTimeout().toMillis()).isGreaterThanOrEqualTo(100);
        p.setFailureBurst(-5);
        assertThat(p.getFailureBurst()).isEqualTo(1);
    }
}
