package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.contentdrafts.ContentDraftRepository;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestion;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestionRepository;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestionStatus;
import com.fdmultimedia.api.contentsources.ContentSourceRepository;
import com.fdmultimedia.api.experiments.Experiment;
import com.fdmultimedia.api.experiments.ExperimentRepository;
import com.fdmultimedia.api.experiments.ExperimentVariant;
import com.fdmultimedia.api.experiments.ExperimentVariantRepository;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.robots.RobotRun;
import com.fdmultimedia.api.robots.RobotRunRepository;
import com.fdmultimedia.api.robots.RobotRunOutput;
import com.fdmultimedia.api.robots.RobotRunOutputRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicationAttributionService {
    private final JdbcTemplate jdbc;
    private final ContentDraftRepository drafts;
    private final ContentSuggestionRepository suggestions;
    private final RobotRunRepository runs;
    private final ContentSourceRepository sources;
    private final ExperimentRepository experiments;
    private final ExperimentVariantRepository experimentVariants;
    private final RobotRunOutputRepository runOutputs;

    public PublicationAttributionService(JdbcTemplate jdbc, ContentDraftRepository drafts,
            ContentSuggestionRepository suggestions, RobotRunRepository runs,
            ContentSourceRepository sources, ExperimentRepository experiments,
            ExperimentVariantRepository experimentVariants, RobotRunOutputRepository runOutputs) {
        this.jdbc = jdbc;
        this.drafts = drafts;
        this.suggestions = suggestions;
        this.runs = runs;
        this.sources = sources;
        this.experiments = experiments;
        this.experimentVariants = experimentVariants;
        this.runOutputs = runOutputs;
    }

    public void capture(Publication publication, UUID scheduleId, UUID scheduledSuggestionId, Instant now) {
        ContentDraft draft = publication.getContentDraftId() == null ? null
                : drafts.findByWorkspaceAndId(publication.getWorkspace(), publication.getContentDraftId()).orElse(null);
        RobotRun run = draft == null || draft.getRobotRunId() == null ? null
                : runs.findByWorkspaceAndId(publication.getWorkspace(), draft.getRobotRunId()).orElse(null);
        RobotRunOutput output = draft == null || draft.getRobotRunOutputId() == null ? null
                : runOutputs.findByWorkspaceAndId(publication.getWorkspace(), draft.getRobotRunOutputId()).orElse(null);
        UUID suggestionId = scheduleId == null
                ? draft == null ? null : draft.getAppliedContentSuggestionId()
                : scheduledSuggestionId;
        ContentSuggestion suggestion = suggestionId == null ? null
                : suggestions.findByWorkspaceAndId(publication.getWorkspace(), suggestionId).orElse(null);
        if (suggestion != null && (suggestion.getStatus() != ContentSuggestionStatus.APPLIED
                || draft == null || !suggestion.getContentDraft().getId().equals(draft.getId()))) {
            suggestion = null;
        }
        if (scheduleId == null && draft != null && !java.util.Objects.equals(draft.getCaption(), publication.getCaption())) {
            suggestion = null;
        }
        String sourceName = run == null || run.getContentSourceId() == null ? null
                : sources.findByWorkspaceAndId(publication.getWorkspace(), run.getContentSourceId())
                        .map(source -> source.getName()).orElse(null);

        // Phase 14A experiment provenance (item 33): frozen here from the RobotRun's own
        // immutable assignment snapshot — never re-derived later from a live, possibly
        // renamed Experiment/Variant. Protocol deviation (item 32/97) is purely
        // descriptive: it never excludes a Publication from anything automatically.
        UUID experimentId = run == null ? null : run.getExperimentId();
        String experimentNameSnapshot = null;
        String experimentVariantLabelSnapshot = null;
        UUID experimentFactorValueId = null;
        String experimentFactorValueNameSnapshot = null;
        Boolean protocolDeviation = null;
        String protocolDeviationReason = null;
        if (experimentId != null) {
            experimentNameSnapshot = experiments.findByWorkspaceAndId(publication.getWorkspace(), experimentId)
                    .map(Experiment::getName).orElse(null);
            ExperimentVariant variant = experimentVariants.findById(run.getExperimentVariantId()).orElse(null);
            if (variant != null) {
                experimentVariantLabelSnapshot = variant.getLabel();
                experimentFactorValueId = variant.getPersonaId();
                experimentFactorValueNameSnapshot = variant.getPersonaNameSnapshot();
            }
            UUID assignmentId = run.getExperimentAssignmentId();
            if (suggestion == null) {
                protocolDeviation = true;
                protocolDeviationReason = "NO_EXPERIMENT_TREATMENT_SUGGESTION_APPLIED";
            } else if (!assignmentId.equals(suggestion.getExperimentAssignmentId())) {
                protocolDeviation = true;
                protocolDeviationReason = "DIFFERENT_SUGGESTION_APPLIED";
            } else {
                protocolDeviation = false;
            }
        }

        // Item 73: only frozen when the applied suggestion actually consumed a campaign plan item's guidance
        // (promptVersion == SOCIAL_COPY_V3_CAMPAIGN) — never merely because a plan existed for this run.
        UUID campaignPlanId = suggestion == null ? null : suggestion.getCampaignPlanId();
        Integer campaignPlanRevision = suggestion == null ? null : suggestion.getCampaignPlanRevision();
        UUID campaignPlanItemId = suggestion == null ? null : suggestion.getCampaignPlanItemId();

        jdbc.update("""
                INSERT INTO publication_attributions (
                    publication_id, workspace_id, content_draft_id, publish_schedule_id,
                    robot_run_id, robot_id, robot_name_snapshot, content_source_id,
                    source_media_asset_id, final_media_asset_id, applied_content_suggestion_id,
                    suggestion_origin, persona_id, persona_name_snapshot, ai_provider, ai_model,
                    prompt_version, ai_policy, robot_autonomy_mode, source_selection_policy,
                    content_source_name_snapshot,
                    experiment_id, experiment_name_snapshot, experiment_factor,
                    experiment_variant_id, experiment_variant_key, experiment_variant_label_snapshot,
                    experiment_factor_value_id, experiment_factor_value_name_snapshot, experiment_assignment_id,
                    protocol_deviation, protocol_deviation_reason,
                    robot_run_output_id, highlight_selection_id, highlight_selection_item_id,
                    highlight_candidate_id, selection_order, source_rank,
                    campaign_plan_id, campaign_plan_revision, campaign_plan_item_id,
                    created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                publication.getId(), publication.getWorkspace().getId(), draft == null ? null : draft.getId(), scheduleId,
                run == null ? null : run.getId(), run == null ? null : run.getRobot().getId(),
                run == null ? null : run.getRobot().getName(), run == null ? null : run.getContentSourceId(),
                draft == null ? publication.getAsset().getId() : draft.getSourceAsset().getId(),
                publication.getAsset().getId(), suggestion == null ? null : suggestion.getId(),
                suggestion == null ? null : suggestion.getOrigin().name(),
                suggestion == null ? null : suggestion.getPersonaId(),
                suggestion == null ? null : suggestion.getPersonaName(),
                suggestion == null ? null : suggestion.getProvider(),
                suggestion == null ? null : suggestion.getModel(),
                suggestion == null ? null : suggestion.getPromptVersion(),
                run == null ? null : run.getAiPolicySnapshot().name(),
                run == null ? null : run.getRobot().getAutonomyMode().name(),
                run == null || run.getSelectionPolicy() == null ? null : run.getSelectionPolicy().name(),
                sourceName,
                experimentId, experimentNameSnapshot, run == null || run.getExperimentFactor() == null ? null : run.getExperimentFactor().name(),
                run == null ? null : run.getExperimentVariantId(),
                run == null || run.getExperimentVariantKey() == null ? null : run.getExperimentVariantKey().name(),
                experimentVariantLabelSnapshot, experimentFactorValueId, experimentFactorValueNameSnapshot,
                run == null ? null : run.getExperimentAssignmentId(),
                protocolDeviation, protocolDeviationReason,
                output == null ? null : output.getId(),
                output == null ? null : output.getHighlightSelection().getId(),
                output == null ? null : output.getSelectionItem().getId(),
                output == null ? null : output.getCandidate().getId(),
                output == null ? null : output.getSelectionOrder(),
                output == null ? null : output.getSourceRank(),
                campaignPlanId, campaignPlanRevision, campaignPlanItemId,
                java.sql.Timestamp.from(now));
    }

    public PublicationAttribution get(UUID workspaceId, UUID publicationId) {
        return jdbc.query("SELECT * FROM publication_attributions WHERE workspace_id = ? AND publication_id = ?",
                this::map, workspaceId, publicationId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Publication attribution not found"));
    }

    private PublicationAttribution map(ResultSet rs, int row) throws SQLException {
        Boolean protocolDeviation = (Boolean) rs.getObject("protocol_deviation");
        return new PublicationAttribution(
                rs.getObject("publication_id", UUID.class), rs.getObject("content_draft_id", UUID.class),
                rs.getObject("publish_schedule_id", UUID.class), rs.getObject("robot_run_id", UUID.class),
                rs.getObject("robot_id", UUID.class), rs.getString("robot_name_snapshot"),
                rs.getObject("content_source_id", UUID.class), rs.getObject("source_media_asset_id", UUID.class),
                rs.getObject("final_media_asset_id", UUID.class), rs.getObject("applied_content_suggestion_id", UUID.class),
                rs.getString("suggestion_origin"), rs.getObject("persona_id", UUID.class),
                rs.getString("persona_name_snapshot"), rs.getString("ai_provider"), rs.getString("ai_model"),
                rs.getString("prompt_version"), rs.getString("ai_policy"), rs.getString("robot_autonomy_mode"),
                rs.getString("source_selection_policy"), rs.getString("content_source_name_snapshot"),
                rs.getObject("experiment_id", UUID.class), rs.getString("experiment_name_snapshot"), rs.getString("experiment_factor"),
                rs.getObject("experiment_variant_id", UUID.class), rs.getString("experiment_variant_key"),
                rs.getString("experiment_variant_label_snapshot"), rs.getObject("experiment_factor_value_id", UUID.class),
                rs.getString("experiment_factor_value_name_snapshot"), rs.getObject("experiment_assignment_id", UUID.class),
                protocolDeviation, rs.getString("protocol_deviation_reason"),
                rs.getObject("robot_run_output_id", UUID.class), rs.getObject("highlight_selection_id", UUID.class),
                rs.getObject("highlight_selection_item_id", UUID.class), rs.getObject("highlight_candidate_id", UUID.class),
                (Integer) rs.getObject("selection_order"), (Integer) rs.getObject("source_rank"),
                rs.getObject("campaign_plan_id", UUID.class), (Integer) rs.getObject("campaign_plan_revision"),
                rs.getObject("campaign_plan_item_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
