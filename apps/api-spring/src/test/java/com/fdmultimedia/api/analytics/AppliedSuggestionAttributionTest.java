package com.fdmultimedia.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.publishschedules.PublishSchedule;
import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppliedSuggestionAttributionTest {
    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    @Test
    void manualCaptionEditClearsPristineAiLinkButTitleEditDoesNot() {
        ContentDraft draft = draft();
        UUID suggestionId = UUID.randomUUID();
        draft.recordAppliedSuggestion(suggestionId);
        draft.updateEditableFields("New title", "Applied caption", NOW);
        assertThat(draft.getAppliedContentSuggestionId()).isEqualTo(suggestionId);
        draft.updateEditableFields("New title", "Human revision", NOW);
        assertThat(draft.getAppliedContentSuggestionId()).isNull();
    }

    @Test
    void scheduleFreezesSuggestionIdAlongsideCaption() {
        ContentDraft draft = draft();
        UUID suggestionId = UUID.randomUUID();
        draft.recordAppliedSuggestion(suggestionId);
        PublishSchedule schedule = new PublishSchedule(mock(Workspace.class), draft, mock(MediaAsset.class),
                mock(SocialAccount.class), draft.getCaption(), NOW.plusSeconds(3600), mock(AppUser.class), NOW);
        draft.updateEditableFields(draft.getTitle(), "Later edit", NOW);
        assertThat(draft.getAppliedContentSuggestionId()).isNull();
        assertThat(schedule.getAppliedContentSuggestionIdSnapshot()).isEqualTo(suggestionId);
    }

    private ContentDraft draft() {
        return ContentDraft.fromExistingAsset(mock(Workspace.class), mock(MediaAsset.class),
                "Title", "Applied caption", mock(AppUser.class), NOW);
    }
}
