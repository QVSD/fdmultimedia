package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExperimentDecisionService {
    private final AuthService auth;
    private final ExperimentRepository experiments;
    private final ExperimentDecisionReadinessService readiness;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ExperimentDecisionService(AuthService auth, ExperimentRepository experiments,
            ExperimentDecisionReadinessService readiness, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.auth = auth;
        this.experiments = experiments;
        this.readiness = readiness;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ExperimentDecisionRecord create(AuthenticatedUser user, UUID experimentId, ExperimentDecisionRequest request) {
        var membership = auth.currentMembershipFor(user);
        UUID workspaceId = membership.getWorkspace().getId();
        Experiment experiment = experiments.findByWorkspaceAndId(membership.getWorkspace(), experimentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found"));
        if (request.idempotencyKey() == null || request.population() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decision key and population are required");
        String type = request.decision();
        String expectedVariant = switch (type == null ? "" : type) {
            case "SELECT_VARIANT_A" -> "A";
            case "SELECT_VARIANT_B" -> "B";
            case "KEEP_CURRENT_CONFIGURATION", "INCONCLUSIVE", "CANCEL_EXPERIMENT" -> null;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid decision");
        };
        if (expectedVariant == null ? request.selectedVariantKey() != null : !expectedVariant.equals(request.selectedVariantKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected variant does not match decision");
        }
        String rationale = request.rationale();
        if (rationale == null || rationale.isBlank() || rationale.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rationale must contain 1 to 2000 characters");
        }
        ExperimentDecisionRecord existing = findByKey(workspaceId, experimentId, request.idempotencyKey());
        if (existing != null) {
            if (!existing.decision().equals(type) || !existing.analysisPopulationSnapshot().equals(request.population().name())
                    || !existing.rationale().equals(rationale) || !java.util.Objects.equals(existing.selectedVariantKey(), expectedVariant)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used for another decision");
            }
            return existing;
        }
        if (experiment.getStatus() == ExperimentStatus.DRAFT) throw new ResponseStatusException(HttpStatus.CONFLICT, "DRAFT_EXPERIMENT_DECISION_UNAVAILABLE");
        if (experiment.getStatus() == ExperimentStatus.CANCELLED && expectedVariant != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CANCELLED_EXPERIMENT_VARIANT_SELECTION_UNAVAILABLE");
        }
        ExperimentDecisionReadiness current = readiness.read(user, experimentId);
        ExperimentDecisionReadiness.Population population = request.population() == AnalysisPopulation.ASSIGNED_OBSERVED
                ? current.assignedObserved() : current.perProtocolObserved();
        var effect = population.evidence().effect();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String fingerprint = fingerprint(current, population);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id).addValue("workspace", workspaceId).addValue("experiment", experimentId)
                .addValue("key", request.idempotencyKey()).addValue("decision", type).addValue("variant", expectedVariant)
                .addValue("rationale", rationale).addValue("user", membership.getUser().getId()).addValue("at", Timestamp.from(now))
                .addValue("guardrail", current.guardrailVersion()).addValue("analysis", current.analysisVersion())
                .addValue("status", current.experimentStatus().name()).addValue("metric", current.primaryMetric())
                .addValue("window", current.targetObservationWindow()).addValue("threshold", current.minimumPracticalEffect())
                .addValue("population", request.population().name()).addValue("na", population.evidence().variantA().metricSampleCount())
                .addValue("nb", population.evidence().variantB().metricSampleCount())
                .addValue("difference", effect.absoluteMeanDifference()).addValue("lower", effect.confidenceIntervalLower())
                .addValue("upper", effect.confidenceIntervalUpper()).addValue("p", effect.pValue())
                .addValue("g", effect.standardizedEffectSize()).addValue("readiness", population.readinessStatus())
                .addValue("fingerprint", fingerprint);
        jdbc.update("""
                INSERT INTO experiment_decisions (id,workspace_id,experiment_id,idempotency_key,decision,selected_variant_key,
                rationale,decided_by_user_id,decided_at,guardrail_version,analysis_version,experiment_status_snapshot,
                primary_metric_snapshot,observation_window_snapshot,minimum_practical_effect_snapshot,analysis_population_snapshot,
                variant_a_sample_size_snapshot,variant_b_sample_size_snapshot,absolute_mean_difference_snapshot,
                confidence_interval_lower_snapshot,confidence_interval_upper_snapshot,p_value_snapshot,
                standardized_effect_size_snapshot,readiness_status_snapshot,evidence_fingerprint)
                VALUES (:id,:workspace,:experiment,:key,:decision,:variant,:rationale,:user,:at,:guardrail,:analysis,
                :status,:metric,:window,:threshold,:population,:na,:nb,:difference,:lower,:upper,:p,:g,:readiness,:fingerprint)
                ON CONFLICT (workspace_id,experiment_id,idempotency_key) DO NOTHING
                """, params);
        ExperimentDecisionRecord saved = findByKey(workspaceId, experimentId, request.idempotencyKey());
        if (saved == null || !saved.decision().equals(type) || !saved.rationale().equals(rationale)
                || !saved.analysisPopulationSnapshot().equals(request.population().name())
                || !java.util.Objects.equals(saved.selectedVariantKey(), expectedVariant)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used for another decision");
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ExperimentDecisionRecord> history(AuthenticatedUser user, UUID experimentId) {
        UUID workspace = requireWorkspace(user, experimentId);
        return jdbc.query("SELECT * FROM experiment_decisions WHERE workspace_id=:workspace AND experiment_id=:experiment ORDER BY decided_at DESC,id DESC LIMIT 100",
                new MapSqlParameterSource("workspace", workspace).addValue("experiment", experimentId), this::map);
    }

    @Transactional(readOnly = true)
    public ExperimentDecisionRecord detail(AuthenticatedUser user, UUID experimentId, UUID decisionId) {
        UUID workspace = requireWorkspace(user, experimentId);
        List<ExperimentDecisionRecord> result = jdbc.query("SELECT * FROM experiment_decisions WHERE workspace_id=:workspace AND experiment_id=:experiment AND id=:id",
                new MapSqlParameterSource("workspace", workspace).addValue("experiment", experimentId).addValue("id", decisionId), this::map);
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Decision not found");
        return result.get(0);
    }

    private UUID requireWorkspace(AuthenticatedUser user, UUID experimentId) {
        var workspace = auth.currentMembershipFor(user).getWorkspace();
        if (experiments.findByWorkspaceAndId(workspace, experimentId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found");
        return workspace.getId();
    }

    private ExperimentDecisionRecord findByKey(UUID workspace, UUID experiment, UUID key) {
        List<ExperimentDecisionRecord> result = jdbc.query("SELECT * FROM experiment_decisions WHERE workspace_id=:workspace AND experiment_id=:experiment AND idempotency_key=:key",
                new MapSqlParameterSource("workspace", workspace).addValue("experiment", experiment).addValue("key", key), this::map);
        return result.isEmpty() ? null : result.get(0);
    }

    private ExperimentDecisionRecord map(ResultSet rs, int row) throws SQLException {
        return new ExperimentDecisionRecord(rs.getObject("id", UUID.class), rs.getObject("experiment_id", UUID.class),
                rs.getObject("idempotency_key", UUID.class), rs.getString("decision"), rs.getString("selected_variant_key"),
                rs.getString("rationale"), rs.getObject("decided_by_user_id", UUID.class), rs.getTimestamp("decided_at").toInstant(),
                rs.getString("guardrail_version"), rs.getString("analysis_version"), rs.getString("experiment_status_snapshot"),
                rs.getString("primary_metric_snapshot"), rs.getString("observation_window_snapshot"),
                rs.getBigDecimal("minimum_practical_effect_snapshot"), rs.getString("analysis_population_snapshot"),
                rs.getLong("variant_a_sample_size_snapshot"), rs.getLong("variant_b_sample_size_snapshot"),
                rs.getBigDecimal("absolute_mean_difference_snapshot"), rs.getBigDecimal("confidence_interval_lower_snapshot"),
                rs.getBigDecimal("confidence_interval_upper_snapshot"), rs.getBigDecimal("p_value_snapshot"),
                rs.getBigDecimal("standardized_effect_size_snapshot"), rs.getString("readiness_status_snapshot"), rs.getString("evidence_fingerprint"));
    }

    static String fingerprint(ExperimentDecisionReadiness readiness, ExperimentDecisionReadiness.Population population) {
        var e = population.evidence();
        var effect = e.effect();
        String canonical = String.join("\n", readiness.experimentId().toString(), readiness.guardrailVersion(),
                readiness.analysisVersion(), population.population().name(), readiness.primaryMetric(),
                readiness.targetObservationWindow(), Long.toString(e.variantA().metricSampleCount()),
                Long.toString(e.variantB().metricSampleCount()), value(effect.absoluteMeanDifference()),
                value(effect.confidenceIntervalLower()), value(effect.confidenceIntervalUpper()), value(effect.pValue()),
                value(effect.standardizedEffectSize()), population.readinessStatus(), value(readiness.minimumPracticalEffect()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String value(BigDecimal value) { return value == null ? "<null>" : value.stripTrailingZeros().toPlainString(); }
}
