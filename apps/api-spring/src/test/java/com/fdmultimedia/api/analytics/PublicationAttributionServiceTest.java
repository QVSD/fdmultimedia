package com.fdmultimedia.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.fdmultimedia.api.contentsources.ContentSource;
import com.fdmultimedia.api.contentsources.ContentSourceRepository;
import com.fdmultimedia.api.experiments.Experiment;
import com.fdmultimedia.api.experiments.ExperimentRepository;
import com.fdmultimedia.api.experiments.ExperimentVariant;
import com.fdmultimedia.api.experiments.ExperimentVariantRepository;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotAiPolicy;
import com.fdmultimedia.api.robots.RobotAutonomyMode;
import com.fdmultimedia.api.robots.RobotRun;
import com.fdmultimedia.api.robots.RobotRunRepository;
import com.fdmultimedia.api.robots.RobotRunOutputRepository;
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
    private final ContentSourceRepository sources = mock(ContentSourceRepository.class);
    private final ExperimentRepository experiments = mock(ExperimentRepository.class);
    private final ExperimentVariantRepository experimentVariants = mock(ExperimentVariantRepository.class);
    private final RobotRunOutputRepository runOutputs = mock(RobotRunOutputRepository.class);
    private final PublicationAttributionService service = new PublicationAttributionService(
            jdbc, drafts, suggestions, runs, sources, experiments, experimentVariants, runOutputs);
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
        ContentSource source = mock(ContentSource.class);
        when(source.getName()).thenReturn("Historical source");
        when(sources.findByWorkspaceAndId(workspace, contentSourceId)).thenReturn(Optional.of(source));
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
        assertThat(jdbc.args[20]).isEqualTo("Historical source");
    }

    @Test
    void protocolDeviationIsFalseWhenTheAppliedSuggestionMatchesTheFrozenAssignment() {
        UUID experimentId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        RobotRun run = experimentalRun(experimentId, variantId, assignmentId);
        ContentSuggestion suggestion = mock(ContentSuggestion.class);
        UUID suggestionId = UUID.randomUUID();
        ContentDraft draft = draftWithAppliedSuggestion(run, suggestionId);
        when(suggestion.getId()).thenReturn(suggestionId);
        when(suggestion.getStatus()).thenReturn(ContentSuggestionStatus.APPLIED);
        when(suggestion.getContentDraft()).thenReturn(draft);
        when(suggestion.getOrigin()).thenReturn(ContentSuggestionOrigin.ROBOT);
        when(suggestion.getExperimentAssignmentId()).thenReturn(assignmentId);
        when(suggestions.findByWorkspaceAndId(workspace, suggestionId)).thenReturn(Optional.of(suggestion));
        ExperimentVariant variant = mock(ExperimentVariant.class);
        when(variant.getLabel()).thenReturn("Bold");
        when(experimentVariants.findById(variantId)).thenReturn(Optional.of(variant));
        when(experiments.findByWorkspaceAndId(any(), any())).thenReturn(Optional.of(mock(Experiment.class)));
        Publication publication = new Publication(workspace, media, mock(SocialAccount.class),
                draft.getCaption(), mock(AppUser.class), draft.getId(), NOW);

        service.capture(publication, null, null, NOW);

        assertThat(jdbc.args[21]).isEqualTo(experimentId);
        assertThat(jdbc.args[30]).isEqualTo(false);
        assertThat(jdbc.args[31]).isNull();
    }

    @Test
    void protocolDeviationIsTrueWhenADifferentSuggestionWasAppliedThanTheOneTheAssignmentGenerated() {
        UUID experimentId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        RobotRun run = experimentalRun(experimentId, variantId, assignmentId);
        ContentSuggestion suggestion = mock(ContentSuggestion.class);
        UUID suggestionId = UUID.randomUUID();
        ContentDraft draft = draftWithAppliedSuggestion(run, suggestionId);
        when(suggestion.getId()).thenReturn(suggestionId);
        when(suggestion.getStatus()).thenReturn(ContentSuggestionStatus.APPLIED);
        when(suggestion.getContentDraft()).thenReturn(draft);
        when(suggestion.getOrigin()).thenReturn(ContentSuggestionOrigin.MANUAL);
        // A manually-applied suggestion never carries this run's own experiment assignment id.
        when(suggestion.getExperimentAssignmentId()).thenReturn(UUID.randomUUID());
        when(suggestions.findByWorkspaceAndId(workspace, suggestionId)).thenReturn(Optional.of(suggestion));
        when(experimentVariants.findById(variantId)).thenReturn(Optional.empty());
        when(experiments.findByWorkspaceAndId(any(), any())).thenReturn(Optional.of(mock(Experiment.class)));
        Publication publication = new Publication(workspace, media, mock(SocialAccount.class),
                draft.getCaption(), mock(AppUser.class), draft.getId(), NOW);

        service.capture(publication, null, null, NOW);

        assertThat(jdbc.args[30]).isEqualTo(true);
        assertThat(jdbc.args[31]).isEqualTo("DIFFERENT_SUGGESTION_APPLIED");
    }

    // ---- Phase 17E: campaign provenance ----

    @Test
    void campaignProvenanceIsFrozenOnlyWhenTheAppliedSuggestionActuallyConsumedGuidance() {
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
        when(run.getId()).thenReturn(runId);
        when(run.getRobot()).thenReturn(robot);
        when(run.getAiPolicySnapshot()).thenReturn(RobotAiPolicy.GENERATE_AND_APPLY);
        when(robot.getAutonomyMode()).thenReturn(RobotAutonomyMode.AUTO_SCHEDULE);
        when(runs.findByWorkspaceAndId(workspace, runId)).thenReturn(Optional.of(run));
        ContentSuggestion suggestion = mock(ContentSuggestion.class);
        UUID campaignPlanId = UUID.randomUUID();
        UUID campaignPlanItemId = UUID.randomUUID();
        when(suggestion.getId()).thenReturn(suggestionId);
        when(suggestion.getStatus()).thenReturn(ContentSuggestionStatus.APPLIED);
        when(suggestion.getContentDraft()).thenReturn(draft);
        when(suggestion.getOrigin()).thenReturn(ContentSuggestionOrigin.ROBOT);
        when(suggestion.getCampaignPlanId()).thenReturn(campaignPlanId);
        when(suggestion.getCampaignPlanRevision()).thenReturn(2);
        when(suggestion.getCampaignPlanItemId()).thenReturn(campaignPlanItemId);
        when(suggestions.findByWorkspaceAndId(workspace, suggestionId)).thenReturn(Optional.of(suggestion));
        Publication publication = new Publication(workspace, media, mock(SocialAccount.class),
                "AI caption", mock(AppUser.class), draft.getId(), NOW);

        service.capture(publication, null, null, NOW);

        assertThat(jdbc.args[38]).isEqualTo(campaignPlanId);
        assertThat(jdbc.args[39]).isEqualTo(2);
        assertThat(jdbc.args[40]).isEqualTo(campaignPlanItemId);
    }

    @Test
    void campaignProvenanceStaysNullWhenNoSuggestionWasApplied() {
        UUID sourceId = UUID.randomUUID();
        when(media.getId()).thenReturn(sourceId);
        Publication publication = new Publication(workspace, media, mock(SocialAccount.class),
                "Manual caption", mock(AppUser.class), NOW);

        service.capture(publication, null, null, NOW);

        assertThat(jdbc.args[38]).isNull();
        assertThat(jdbc.args[39]).isNull();
        assertThat(jdbc.args[40]).isNull();
    }

    @Test
    void campaignProvenanceStaysNullWhenTheAppliedSuggestionNeverConsumedCampaignGuidance() {
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
        when(run.getId()).thenReturn(runId);
        when(run.getRobot()).thenReturn(robot);
        when(run.getAiPolicySnapshot()).thenReturn(RobotAiPolicy.GENERATE_AND_APPLY);
        when(robot.getAutonomyMode()).thenReturn(RobotAutonomyMode.AUTO_SCHEDULE);
        when(runs.findByWorkspaceAndId(workspace, runId)).thenReturn(Optional.of(run));
        // This run's Robot had a campaign plan configured, but THIS suggestion's own promptVersion
        // was plain V2 (no guidance consumed) — its campaign getters return null, exactly like a
        // suggestion generated before Phase 17E existed. Provenance must stay null either way.
        ContentSuggestion suggestion = mock(ContentSuggestion.class);
        when(suggestion.getId()).thenReturn(suggestionId);
        when(suggestion.getStatus()).thenReturn(ContentSuggestionStatus.APPLIED);
        when(suggestion.getContentDraft()).thenReturn(draft);
        when(suggestion.getOrigin()).thenReturn(ContentSuggestionOrigin.ROBOT);
        // Mockito's default answer returns 0 (not null) for an unstubbed Integer getter,
        // so stub explicitly — the real entity's field is genuinely null when unset.
        when(suggestion.getCampaignPlanId()).thenReturn(null);
        when(suggestion.getCampaignPlanRevision()).thenReturn(null);
        when(suggestion.getCampaignPlanItemId()).thenReturn(null);
        when(suggestions.findByWorkspaceAndId(workspace, suggestionId)).thenReturn(Optional.of(suggestion));
        Publication publication = new Publication(workspace, media, mock(SocialAccount.class),
                "AI caption", mock(AppUser.class), draft.getId(), NOW);

        service.capture(publication, null, null, NOW);

        assertThat(jdbc.args[38]).isNull();
        assertThat(jdbc.args[39]).isNull();
        assertThat(jdbc.args[40]).isNull();
    }

    private RobotRun experimentalRun(UUID experimentId, UUID variantId, UUID assignmentId) {
        RobotRun run = mock(RobotRun.class);
        UUID runId = UUID.randomUUID();
        Robot robot = mock(Robot.class);
        when(run.getId()).thenReturn(runId);
        when(run.getRobot()).thenReturn(robot);
        when(run.getAiPolicySnapshot()).thenReturn(RobotAiPolicy.GENERATE_AND_APPLY);
        when(robot.getAutonomyMode()).thenReturn(RobotAutonomyMode.AUTO_SCHEDULE);
        when(run.getExperimentId()).thenReturn(experimentId);
        when(run.getExperimentAssignmentId()).thenReturn(assignmentId);
        when(run.getExperimentVariantId()).thenReturn(variantId);
        when(run.getExperimentVariantKey()).thenReturn(com.fdmultimedia.api.experiments.ExperimentVariantKey.A);
        when(run.getExperimentFactor()).thenReturn(com.fdmultimedia.api.experiments.ExperimentFactor.PERSONA);
        when(runs.findByWorkspaceAndId(workspace, runId)).thenReturn(Optional.of(run));
        return run;
    }

    private ContentDraft draftWithAppliedSuggestion(RobotRun run, UUID suggestionId) {
        ContentDraft draft = ContentDraft.fromExistingAsset(workspace, media, "Title", "Applied caption", mock(AppUser.class), NOW);
        draft.attachRobotRun(run.getId());
        draft.recordAppliedSuggestion(suggestionId);
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));
        return draft;
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
