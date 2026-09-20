package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.contentdrafts.ContentDraftRepository;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestion;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestionRepository;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestionStatus;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.robots.RobotRun;
import com.fdmultimedia.api.robots.RobotRunRepository;
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

    public PublicationAttributionService(JdbcTemplate jdbc, ContentDraftRepository drafts,
            ContentSuggestionRepository suggestions, RobotRunRepository runs) {
        this.jdbc = jdbc;
        this.drafts = drafts;
        this.suggestions = suggestions;
        this.runs = runs;
    }

    public void capture(Publication publication, UUID scheduleId, UUID scheduledSuggestionId, Instant now) {
        ContentDraft draft = publication.getContentDraftId() == null ? null
                : drafts.findByWorkspaceAndId(publication.getWorkspace(), publication.getContentDraftId()).orElse(null);
        RobotRun run = draft == null || draft.getRobotRunId() == null ? null
                : runs.findByWorkspaceAndId(publication.getWorkspace(), draft.getRobotRunId()).orElse(null);
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
        jdbc.update("""
                INSERT INTO publication_attributions (
                    publication_id, workspace_id, content_draft_id, publish_schedule_id,
                    robot_run_id, robot_id, robot_name_snapshot, content_source_id,
                    source_media_asset_id, final_media_asset_id, applied_content_suggestion_id,
                    suggestion_origin, persona_id, persona_name_snapshot, ai_provider, ai_model,
                    prompt_version, ai_policy, robot_autonomy_mode, source_selection_policy, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                java.sql.Timestamp.from(now));
    }

    public PublicationAttribution get(UUID workspaceId, UUID publicationId) {
        return jdbc.query("SELECT * FROM publication_attributions WHERE workspace_id = ? AND publication_id = ?",
                this::map, workspaceId, publicationId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Publication attribution not found"));
    }

    private PublicationAttribution map(ResultSet rs, int row) throws SQLException {
        return new PublicationAttribution(
                rs.getObject("publication_id", UUID.class), rs.getObject("content_draft_id", UUID.class),
                rs.getObject("publish_schedule_id", UUID.class), rs.getObject("robot_run_id", UUID.class),
                rs.getObject("robot_id", UUID.class), rs.getString("robot_name_snapshot"),
                rs.getObject("content_source_id", UUID.class), rs.getObject("source_media_asset_id", UUID.class),
                rs.getObject("final_media_asset_id", UUID.class), rs.getObject("applied_content_suggestion_id", UUID.class),
                rs.getString("suggestion_origin"), rs.getObject("persona_id", UUID.class),
                rs.getString("persona_name_snapshot"), rs.getString("ai_provider"), rs.getString("ai_model"),
                rs.getString("prompt_version"), rs.getString("ai_policy"), rs.getString("robot_autonomy_mode"),
                rs.getString("source_selection_policy"), rs.getTimestamp("created_at").toInstant());
    }
}
