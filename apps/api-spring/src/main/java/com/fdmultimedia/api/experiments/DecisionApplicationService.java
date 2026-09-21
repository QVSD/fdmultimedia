package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.personas.PersonaStatus;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DecisionApplicationService {
    public static final String VERSION = "DECISION_APPLICATION_V1";
    private final AuthService auth;
    private final ExperimentRepository experiments;
    private final ExperimentVariantRepository variants;
    private final ExperimentDecisionService decisions;
    private final RobotRepository robots;
    private final PersonaRepository personas;
    private final NamedParameterJdbcTemplate jdbc;
    private final DecisionApplicationProperties properties;
    private final Clock clock;

    public DecisionApplicationService(AuthService auth, ExperimentRepository experiments,
            ExperimentVariantRepository variants, ExperimentDecisionService decisions, RobotRepository robots,
            PersonaRepository personas, NamedParameterJdbcTemplate jdbc, DecisionApplicationProperties properties,
            Clock clock) {
        this.auth = auth; this.experiments = experiments; this.variants = variants; this.decisions = decisions;
        this.robots = robots; this.personas = personas; this.jdbc = jdbc; this.properties = properties; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DecisionApplicationPreview preview(AuthenticatedUser user, UUID experimentId, UUID decisionId, UUID robotId) {
        var workspace = auth.currentMembershipFor(user).getWorkspace();
        Experiment experiment = experiments.findByWorkspaceAndId(workspace, experimentId)
                .orElseThrow(() -> notFound("Experiment not found"));
        ExperimentDecisionRecord decision = decisions.detail(user, experimentId, decisionId);
        Robot robot = robots.findByWorkspaceAndId(workspace, robotId).orElseThrow(() -> notFound("Robot not found"));
        return buildPreview(workspace.getId(), experiment, decision, robot);
    }

    @Transactional
    public DecisionApplicationRecord apply(AuthenticatedUser user, UUID experimentId, UUID decisionId,
            DecisionApplicationRequest request) {
        var membership = auth.currentMembershipFor(user);
        UUID workspaceId = membership.getWorkspace().getId();
        DecisionApplicationRecord prior = byKey(workspaceId, decisionId, request.robotId(), request.idempotencyKey());
        if (prior != null) {
            if (!prior.previewFingerprint().equals(request.previewFingerprint()) || prior.rollbackOfApplicationId() != null)
                throw conflict("APPLICATION_IDEMPOTENCY_CONFLICT");
            return prior;
        }
        if (!properties.isEnabled()) throw conflict("DECISION_APPLICATION_DISABLED");
        Experiment experiment = experiments.findByWorkspaceAndId(membership.getWorkspace(), experimentId)
                .orElseThrow(() -> notFound("Experiment not found"));
        ExperimentDecisionRecord decision = decisions.detail(user, experimentId, decisionId);
        Robot robot = robots.findByWorkspaceAndIdForUpdate(membership.getWorkspace(), request.robotId())
                .orElseThrow(() -> notFound("Robot not found"));
        prior = byKey(workspaceId, decisionId, request.robotId(), request.idempotencyKey());
        if (prior != null) {
            if (!prior.previewFingerprint().equals(request.previewFingerprint()) || prior.rollbackOfApplicationId() != null)
                throw conflict("APPLICATION_IDEMPOTENCY_CONFLICT");
            return prior;
        }
        DecisionApplicationPreview preview = buildPreview(workspaceId, experiment, decision, robot);
        if (!preview.previewFingerprint().equals(request.previewFingerprint())) throw conflict("APPLICATION_PREVIEW_STALE");
        if (!preview.eligible()) throw conflict(String.join(",", preview.blockingReasons()));
        requireConfirmations(preview, request);
        Persona target = personas.findByWorkspaceAndId(membership.getWorkspace(), preview.targetPersona().id())
                .orElseThrow(() -> conflict("TARGET_PERSONA_NOT_FOUND"));
        Instant before = robot.getUpdatedAt();
        Persona previous = robot.getPersona();
        Instant now = Instant.now(clock);
        if (!preview.noOp()) robot.applyPersona(target, now);
        return insert(workspaceId, experiment, decision, robot, previous, target, preview, membership.getUser().getId(),
                now, before, robot.getUpdatedAt(), preview.noOp(), request.confirmOlderDecision(),
                request.confirmNotReadyDecision(), request.confirmActiveExperiment(), null, request.idempotencyKey(), "APPLIED");
    }

    @Transactional(readOnly = true)
    public DecisionApplicationPreview rollbackPreview(AuthenticatedUser user, UUID applicationId) {
        var workspace = auth.currentMembershipFor(user).getWorkspace();
        DecisionApplicationRecord original = detailForWorkspace(workspace.getId(), applicationId);
        Robot robot = robots.findByWorkspaceAndId(workspace, original.robotId()).orElseThrow(() -> notFound("Robot not found"));
        return buildRollbackPreview(workspace.getId(), original, robot);
    }

    @Transactional
    public DecisionApplicationRecord rollback(AuthenticatedUser user, UUID applicationId, DecisionRollbackRequest request) {
        var membership = auth.currentMembershipFor(user);
        UUID workspaceId = membership.getWorkspace().getId();
        DecisionApplicationRecord original = detailForWorkspace(workspaceId, applicationId);
        DecisionApplicationRecord prior = byKey(workspaceId, original.experimentDecisionId(), original.robotId(), request.idempotencyKey());
        if (prior != null) {
            if (!Objects.equals(prior.rollbackOfApplicationId(), applicationId) || !prior.previewFingerprint().equals(request.previewFingerprint()))
                throw conflict("APPLICATION_IDEMPOTENCY_CONFLICT");
            return prior;
        }
        if (!properties.isEnabled()) throw conflict("DECISION_APPLICATION_DISABLED");
        if (original.rollbackOfApplicationId() != null) throw conflict("ROLLBACK_OF_ROLLBACK_UNAVAILABLE");
        if (rollbackExists(workspaceId, applicationId)) throw conflict("APPLICATION_ALREADY_ROLLED_BACK");
        Robot robot = robots.findByWorkspaceAndIdForUpdate(membership.getWorkspace(), original.robotId())
                .orElseThrow(() -> notFound("Robot not found"));
        prior = byKey(workspaceId, original.experimentDecisionId(), original.robotId(), request.idempotencyKey());
        if (prior != null) {
            if (!Objects.equals(prior.rollbackOfApplicationId(), applicationId) || !prior.previewFingerprint().equals(request.previewFingerprint()))
                throw conflict("APPLICATION_IDEMPOTENCY_CONFLICT");
            return prior;
        }
        DecisionApplicationPreview preview = buildRollbackPreview(workspaceId, original, robot);
        if (!preview.previewFingerprint().equals(request.previewFingerprint())) throw conflict("APPLICATION_PREVIEW_STALE");
        if (!preview.eligible()) throw conflict(String.join(",", preview.blockingReasons()));
        Persona previous = original.previousPersonaId() == null ? null : personas.findByWorkspaceAndId(membership.getWorkspace(), original.previousPersonaId())
                .orElseThrow(() -> conflict("ROLLBACK_PERSONA_NOT_FOUND"));
        Instant before = robot.getUpdatedAt();
        Persona current = robot.getPersona();
        Instant now = Instant.now(clock);
        if (!preview.noOp()) robot.applyPersona(previous, now);
        Experiment experiment = experiments.findByWorkspaceAndId(membership.getWorkspace(), original.experimentId())
                .orElseThrow(() -> notFound("Experiment not found"));
        ExperimentDecisionRecord decision = decisions.detail(user, original.experimentId(), original.experimentDecisionId());
        return insert(workspaceId, experiment, decision, robot, current, previous, preview, membership.getUser().getId(),
                now, before, robot.getUpdatedAt(), preview.noOp(), false, false, false, applicationId,
                request.idempotencyKey(), "ROLLED_BACK");
    }

    @Transactional(readOnly = true)
    public List<DecisionApplicationRecord> history(AuthenticatedUser user, UUID experimentId) {
        var workspace = auth.currentMembershipFor(user).getWorkspace();
        if (experiments.findByWorkspaceAndId(workspace, experimentId).isEmpty()) throw notFound("Experiment not found");
        return jdbc.query("SELECT * FROM decision_applications WHERE workspace_id=:w AND experiment_id=:e ORDER BY applied_at DESC,id DESC LIMIT 100",
                new MapSqlParameterSource("w", workspace.getId()).addValue("e", experimentId), this::map);
    }

    @Transactional(readOnly = true)
    public DecisionApplicationRecord detail(AuthenticatedUser user, UUID applicationId) {
        return detailForWorkspace(auth.currentMembershipFor(user).getWorkspace().getId(), applicationId);
    }

    private DecisionApplicationPreview buildPreview(UUID workspaceId, Experiment experiment, ExperimentDecisionRecord decision, Robot robot) {
        List<String> blocked = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        if (experiment.getFactor() != ExperimentFactor.PERSONA) blocked.add("UNSUPPORTED_EXPERIMENT_FACTOR");
        String expected = "SELECT_VARIANT_A".equals(decision.decision()) ? "A" : "SELECT_VARIANT_B".equals(decision.decision()) ? "B" : null;
        if (expected == null) blocked.add("UNSUPPORTED_DECISION");
        if (expected != null && !expected.equals(decision.selectedVariantKey())) blocked.add("DECISION_VARIANT_MISMATCH");
        if (decision.evidenceFingerprint() == null || !decision.evidenceFingerprint().matches("[0-9a-f]{64}")) blocked.add("DECISION_EVIDENCE_INVALID");
        if (experiment.getStatus() == ExperimentStatus.DRAFT) blocked.add("EXPERIMENT_DRAFT");
        if (experiment.getStatus() == ExperimentStatus.CANCELLED) blocked.add("EXPERIMENT_CANCELLED");
        if (!Objects.equals(robot.getExperimentId(), experiment.getId())) blocked.add("ROBOT_NOT_LINKED_TO_EXPERIMENT");
        ExperimentVariant variant = expected == null ? null : variants.findByExperimentAndVariantKey(experiment, ExperimentVariantKey.valueOf(expected)).orElse(null);
        if (variant == null && expected != null) blocked.add("TARGET_PERSONA_NOT_FOUND");
        Persona target = variant == null ? null : personas.findByWorkspaceAndId(experiment.getWorkspace(), variant.getPersonaId()).orElse(null);
        if (variant != null && target == null) blocked.add("TARGET_PERSONA_NOT_FOUND");
        if (target != null && target.getStatus() != PersonaStatus.ACTIVE) blocked.add("TARGET_PERSONA_ARCHIVED");
        if (experiment.getStatus() == ExperimentStatus.ACTIVE) warnings.add("EXPERIMENT_STILL_ACTIVE");
        if (experiment.getStatus() == ExperimentStatus.PAUSED) warnings.add("EXPERIMENT_PAUSED");
        if ("NOT_READY".equals(decision.readinessStatusSnapshot())) warnings.add("DECISION_RECORDED_WHILE_NOT_READY");
        if (newerDecisionExists(workspaceId, experiment.getId(), decision.decidedAt())) warnings.add("NEWER_DECISION_EXISTS");
        Persona current = robot.getPersona(); boolean noOp = target != null && current != null && current.getId().equals(target.getId());
        if (noOp) warnings.add("NO_OPERATIONAL_CHANGE");
        var currentRef = ref(current); var targetRef = target == null ? new DecisionApplicationPreview.PersonaRef(
                variant == null ? null : variant.getPersonaId(), variant == null ? null : variant.getPersonaNameSnapshot(), "MISSING") : ref(target);
        String fp = fingerprint(Arrays.asList(VERSION, workspaceId, experiment.getId(), decision.id(), decision.evidenceFingerprint(),
                decision.decision(), decision.selectedVariantKey(), robot.getId(), robot.getUpdatedAt(), id(current), id(target),
                experiment.getStatus(), robot.getExperimentId(), target == null ? "MISSING" : target.getStatus()));
        return new DecisionApplicationPreview(VERSION, experiment.getId(), experiment.getName(), decision.id(), decision.decision(),
                decision.readinessStatusSnapshot(), decision.analysisPopulationSnapshot(), decision.evidenceFingerprint(),
                decision.selectedVariantKey(), robot.getId(), robot.getName(), robot.getExperimentId(), currentRef, targetRef,
                noOp, blocked.isEmpty(), List.copyOf(blocked), List.copyOf(warnings),
                List.of(new DecisionApplicationPreview.Change("personaId", currentRef, targetRef)), fp);
    }

    private DecisionApplicationPreview buildRollbackPreview(UUID workspaceId, DecisionApplicationRecord original, Robot robot) {
        List<String> blocked = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        Persona current = robot.getPersona();
        if (!Objects.equals(id(current), original.targetPersonaId())) blocked.add("ROLLBACK_TARGET_DIVERGED");
        Persona target = original.previousPersonaId() == null ? null : personas.findByWorkspaceAndId(robot.getWorkspace(), original.previousPersonaId()).orElse(null);
        if (original.previousPersonaId() != null && target == null) blocked.add("ROLLBACK_PERSONA_NOT_FOUND");
        if (target != null && target.getStatus() != PersonaStatus.ACTIVE) blocked.add("ROLLBACK_PERSONA_ARCHIVED");
        boolean noOp = Objects.equals(id(current), id(target)); if (noOp) warnings.add("NO_OPERATIONAL_CHANGE");
        var currentRef = ref(current); var targetRef = target == null ? new DecisionApplicationPreview.PersonaRef(null, null, "NONE") : ref(target);
        String fp = fingerprint(Arrays.asList(VERSION, workspaceId, original.id(), original.previewFingerprint(), robot.getId(),
                robot.getUpdatedAt(), id(current), id(target), target == null ? "NONE" : target.getStatus()));
        return new DecisionApplicationPreview(VERSION, original.experimentId(), original.experimentNameSnapshot(),
                original.experimentDecisionId(), "ROLLBACK", null, null, original.decisionEvidenceFingerprint(),
                original.selectedVariantKey(), robot.getId(), robot.getName(), robot.getExperimentId(), currentRef, targetRef,
                noOp, blocked.isEmpty(), List.copyOf(blocked), List.copyOf(warnings),
                List.of(new DecisionApplicationPreview.Change("personaId", currentRef, targetRef)), fp);
    }

    private void requireConfirmations(DecisionApplicationPreview p, DecisionApplicationRequest r) {
        if (p.warnings().contains("EXPERIMENT_STILL_ACTIVE") && !r.confirmActiveExperiment()) throw conflict("CONFIRM_ACTIVE_EXPERIMENT_REQUIRED");
        if (p.warnings().contains("DECISION_RECORDED_WHILE_NOT_READY") && !r.confirmNotReadyDecision()) throw conflict("CONFIRM_NOT_READY_DECISION_REQUIRED");
        if (p.warnings().contains("NEWER_DECISION_EXISTS") && !r.confirmOlderDecision()) throw conflict("CONFIRM_OLDER_DECISION_REQUIRED");
    }

    private DecisionApplicationRecord insert(UUID workspace, Experiment experiment, ExperimentDecisionRecord decision,
            Robot robot, Persona previous, Persona target, DecisionApplicationPreview preview, UUID user, Instant now,
            Instant before, Instant after, boolean noOp, boolean older, boolean notReady, boolean active,
            UUID rollbackOf, String key, String status) {
        UUID id = UUID.randomUUID();
        var p = new MapSqlParameterSource().addValue("id", id).addValue("w", workspace).addValue("e", experiment.getId())
                .addValue("en", experiment.getName()).addValue("d", decision.id()).addValue("r", robot.getId())
                .addValue("rn", robot.getName()).addValue("s", status).addValue("v", decision.selectedVariantKey())
                .addValue("tp", id(target)).addValue("tn", target == null ? "No Persona" : target.getName())
                .addValue("pp", id(previous)).addValue("pn", previous == null ? null : previous.getName())
                .addValue("pf", preview.previewFingerprint()).addValue("ef", decision.evidenceFingerprint()).addValue("u", user)
                .addValue("at", Timestamp.from(now)).addValue("before", Timestamp.from(before)).addValue("after", Timestamp.from(after))
                .addValue("noop", noOp).addValue("older", older).addValue("nr", notReady).addValue("active", active)
                .addValue("rollback", rollbackOf).addValue("key", key);
        jdbc.update("""
                INSERT INTO decision_applications (id,workspace_id,experiment_id,experiment_name_snapshot,experiment_decision_id,
                robot_id,robot_name_snapshot,application_version,status,selected_variant_key,target_persona_id,target_persona_name_snapshot,
                previous_persona_id,previous_persona_name_snapshot,preview_fingerprint,decision_evidence_fingerprint,
                requested_by_user_id,requested_at,applied_by_user_id,applied_at,robot_updated_at_before,robot_updated_at_after,
                no_op,confirmed_older_decision,confirmed_not_ready_decision,confirmed_active_experiment,rollback_of_application_id,
                idempotency_key,created_at) VALUES (:id,:w,:e,:en,:d,:r,:rn,'DECISION_APPLICATION_V1',:s,:v,:tp,:tn,:pp,:pn,
                :pf,:ef,:u,:at,:u,:at,:before,:after,:noop,:older,:nr,:active,:rollback,:key,:at)
                """, p);
        return detailForWorkspace(workspace, id);
    }

    private boolean newerDecisionExists(UUID workspace, UUID experiment, Instant decidedAt) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM experiment_decisions WHERE workspace_id=:w AND experiment_id=:e AND decided_at>:at",
                new MapSqlParameterSource("w", workspace).addValue("e", experiment).addValue("at", Timestamp.from(decidedAt)), Long.class);
        return n != null && n > 0;
    }
    private boolean rollbackExists(UUID workspace, UUID original) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM decision_applications WHERE workspace_id=:w AND rollback_of_application_id=:id",
                new MapSqlParameterSource("w", workspace).addValue("id", original), Long.class);
        return n != null && n > 0;
    }
    private DecisionApplicationRecord byKey(UUID w, UUID d, UUID r, String key) {
        List<DecisionApplicationRecord> found = jdbc.query("SELECT * FROM decision_applications WHERE workspace_id=:w AND experiment_decision_id=:d AND robot_id=:r AND idempotency_key=:k",
                new MapSqlParameterSource("w", w).addValue("d", d).addValue("r", r).addValue("k", key), this::map);
        return found.isEmpty() ? null : found.get(0);
    }
    private DecisionApplicationRecord detailForWorkspace(UUID w, UUID id) {
        List<DecisionApplicationRecord> found = jdbc.query("SELECT * FROM decision_applications WHERE workspace_id=:w AND id=:id",
                new MapSqlParameterSource("w", w).addValue("id", id), this::map);
        if (found.isEmpty()) throw notFound("Decision application not found"); return found.get(0);
    }
    private DecisionApplicationRecord map(ResultSet rs, int row) throws SQLException {
        return new DecisionApplicationRecord(rs.getObject("id", UUID.class), rs.getObject("experiment_id", UUID.class),
                rs.getString("experiment_name_snapshot"), rs.getObject("experiment_decision_id", UUID.class),
                rs.getObject("robot_id", UUID.class), rs.getString("robot_name_snapshot"), rs.getString("application_version"),
                rs.getString("status"), rs.getString("selected_variant_key"), rs.getObject("target_persona_id", UUID.class),
                rs.getString("target_persona_name_snapshot"), rs.getObject("previous_persona_id", UUID.class),
                rs.getString("previous_persona_name_snapshot"), rs.getString("preview_fingerprint"),
                rs.getString("decision_evidence_fingerprint"), rs.getObject("requested_by_user_id", UUID.class),
                rs.getTimestamp("requested_at").toInstant(), rs.getObject("applied_by_user_id", UUID.class),
                rs.getTimestamp("applied_at").toInstant(), rs.getTimestamp("robot_updated_at_before").toInstant(),
                rs.getTimestamp("robot_updated_at_after").toInstant(), rs.getBoolean("no_op"),
                rs.getBoolean("confirmed_older_decision"), rs.getBoolean("confirmed_not_ready_decision"),
                rs.getBoolean("confirmed_active_experiment"), rs.getObject("rollback_of_application_id", UUID.class),
                rs.getString("idempotency_key"));
    }
    private static DecisionApplicationPreview.PersonaRef ref(Persona p) { return p == null ? new DecisionApplicationPreview.PersonaRef(null, null, "NONE") : new DecisionApplicationPreview.PersonaRef(p.getId(), p.getName(), p.getStatus().name()); }
    private static UUID id(Persona p) { return p == null ? null : p.getId(); }
    private static String fingerprint(List<?> values) { try { String canonical = values.stream().map(v -> v == null ? "<null>" : v.toString()).reduce((a,b)->a+"\n"+b).orElse(""); return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
