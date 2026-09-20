package com.fdmultimedia.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.contentdrafts.ContentDraftRepository;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestion;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestionOrigin;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestionRepository;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestionStatus;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotAiPolicy;
import com.fdmultimedia.api.robots.RobotAutonomyMode;
import com.fdmultimedia.api.robots.RobotRun;
import com.fdmultimedia.api.robots.RobotRunRepository;
import com.fdmultimedia.api.robots.RobotSelectionPolicy;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PublicationAttributionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final ContentDraftRepository drafts = mock(ContentDraftRepository.class);
    private final ContentSuggestionRepository suggestions = mock(ContentSuggestionRepository.class);
    private final RobotRunRepository runs = mock(RobotRunRepository.class);
    private final PublicationAttributionService service = new PublicationAttributionService(jdbc, drafts, suggestions, runs);
    private final Workspace workspace = new Workspace("Media", "media");
    private final MediaAsset media = mock(MediaAsset.class);

    @Test
    void manualPublicationCapturesOnlyProvenSourceAndFinalAsset() {
        UUID mediaId = UUID.randomUUID();
        when(media.getId()).thenReturn(mediaId);
        Publication publication = new Publication(workspace, media, mock(SocialAccount.class),
                "Manual caption", mock(AppUser.class), NOW);

        service.capture(publication, null, null, NOW);

        assertThat(jdbc.args[0]).isEqualTo(publication.getId());
        assertThat(jdbc.args[2]).isNull();
        assertThat(jdbc.args[4]).isNull();
        assertThat(jdbc.args[8]).isEqualTo(mediaId);
        assertThat(jdbc.args[9]).isEqualTo(mediaId);
        assertThat(jdbc.args[10]).isNull();
    }

    @Test
    void robotAndSuggestionSnapshotsSurviveLaterNamesChanging() {
        UUID sourceId = UUID.randomUUID();
        when(media.getId()).thenReturn(sourceId);
        ContentDraft draft = ContentDraft.fromExistingAsset(workspace, media, "Title", "AI caption", mock(AppUser.class), NOW);
        UUID runId = UUID.randomUUID();
        draft.attachRobotRun(runId);
        UUID suggestionId = UUID.randomUUID();
        draft.recordAppliedSuggestion(suggestionId);
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));
        RobotRun run = mock(RobotRun.class);
        Robot robot = mock(Robot.class);
        UUID robotId = UUID.randomUUID();
        UUID contentSourceId = UUID.randomUUID();
        when(run.getId()).thenReturn(runId);
        when(run.getRobot()).thenReturn(robot);
        when(run.getContentSourceId()).thenReturn(contentSourceId);
        when(run.getAiPolicySnapshot()).thenReturn(RobotAiPolicy.GENERATE_AND_APPLY);
        when(run.getSelectionPolicy()).thenReturn(RobotSelectionPolicy.OLDEST_UNPROCESSED);
        when(robot.getId()).thenReturn(robotId);
        when(robot.getName()).thenReturn("Historical robot", "Renamed robot");
        when(robot.getAutonomyMode()).thenReturn(RobotAutonomyMode.AUTO_SCHEDULE);
        when(runs.findByWorkspaceAndId(workspace, runId)).thenReturn(Optional.of(run));
        ContentSuggestion suggestion = mock(ContentSuggestion.class);
        UUID personaId = UUID.randomUUID();
        when(suggestion.getId()).thenReturn(suggestionId);
        when(suggestion.getStatus()).thenReturn(ContentSuggestionStatus.APPLIED);
        when(suggestion.getContentDraft()).thenReturn(draft);
        when(suggestion.getOrigin()).thenReturn(ContentSuggestionOrigin.ROBOT);
        when(suggestion.getPersonaId()).thenReturn(personaId);
        when(suggestion.getPersonaName()).thenReturn("Historical persona");
        when(suggestion.getProvider()).thenReturn("DETERMINISTIC_TEST");
        when(suggestion.getModel()).thenReturn("deterministic-v1");
        when(suggestion.getPromptVersion()).thenReturn("SOCIAL_COPY_V2");
        when(suggestions.findByWorkspaceAndId(workspace, suggestionId)).thenReturn(Optional.of(suggestion));
        Publication publication = new Publication(workspace, media, mock(SocialAccount.class),
                "AI caption", mock(AppUser.class), draft.getId(), NOW);

        service.capture(publication, null, null, NOW);

        assertThat(jdbc.args[4]).isEqualTo(runId);
        assertThat(jdbc.args[5]).isEqualTo(robotId);
        assertThat(jdbc.args[6]).isEqualTo("Historical robot");
        assertThat(jdbc.args[7]).isEqualTo(contentSourceId);
        assertThat(jdbc.args[10]).isEqualTo(suggestionId);
        assertThat(jdbc.args[11]).isEqualTo("ROBOT");
        assertThat(jdbc.args[12]).isEqualTo(personaId);
        assertThat(jdbc.args[13]).isEqualTo("Historical persona");
        assertThat(jdbc.args[14]).isEqualTo("DETERMINISTIC_TEST");
        assertThat(jdbc.args[16]).isEqualTo("SOCIAL_COPY_V2");
        assertThat(jdbc.args[17]).isEqualTo("GENERATE_AND_APPLY");
        assertThat(jdbc.args[19]).isEqualTo("OLDEST_UNPROCESSED");
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private Object[] args;

        @Override
        public int update(String sql, Object... args) {
            this.args = args;
            return 1;
        }
    }
}
