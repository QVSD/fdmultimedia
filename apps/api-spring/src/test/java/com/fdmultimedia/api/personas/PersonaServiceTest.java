package com.fdmultimedia.api.personas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PersonaServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final PersonaRepository personas = mock(PersonaRepository.class);
    private final PersonaService service = new PersonaService(authService, personas, Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        when(personas.save(any(Persona.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsPersonaWithDefaultsWhenLanguageAndToneOmitted() {
        PersonaSummary summary = service.create(user, new CreatePersonaRequest(
                "Tech Romania", "desc", null, null, "Founders", "Direct voice.", null, null, null, null));

        assertThat(summary.name()).isEqualTo("Tech Romania");
        assertThat(summary.status()).isEqualTo(PersonaStatus.ACTIVE);
        assertThat(summary.defaultLanguage()).isEqualTo(SuggestionLanguage.AUTO);
        assertThat(summary.defaultTone()).isEqualTo(SuggestionTone.NEUTRAL);
    }

    @Test
    void createsPersonaWithExplicitDefaults() {
        PersonaSummary summary = service.create(user, new CreatePersonaRequest(
                "Tech Romania", null, SuggestionLanguage.ROMANIAN, SuggestionTone.ENERGETIC, "Founders", "Direct voice.",
                "Short sentences.", "Jargon.", "Few hashtags.", "Example text."));

        assertThat(summary.defaultLanguage()).isEqualTo(SuggestionLanguage.ROMANIAN);
        assertThat(summary.defaultTone()).isEqualTo(SuggestionTone.ENERGETIC);
        assertThat(summary.audience()).isEqualTo("Founders");
        assertThat(summary.styleGuidelines()).isEqualTo("Short sentences.");
        assertThat(summary.avoidGuidelines()).isEqualTo("Jargon.");
        assertThat(summary.hashtagGuidelines()).isEqualTo("Few hashtags.");
        assertThat(summary.exampleCopy()).isEqualTo("Example text.");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> service.create(user, new CreatePersonaRequest(
                "  ", null, null, null, null, "Voice.", null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsBlankVoiceDescription() {
        assertThatThrownBy(() -> service.create(user, new CreatePersonaRequest(
                "Name", null, null, null, null, "   ", null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsNameOverMaximumLength() {
        String tooLong = "n".repeat(101);
        assertThatThrownBy(() -> service.create(user, new CreatePersonaRequest(
                tooLong, null, null, null, null, "Voice.", null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsVoiceDescriptionOverMaximumLength() {
        String tooLong = "v".repeat(1001);
        assertThatThrownBy(() -> service.create(user, new CreatePersonaRequest(
                "Name", null, null, null, null, tooLong, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsStyleGuidelinesOverMaximumLength() {
        String tooLong = "s".repeat(2001);
        assertThatThrownBy(() -> service.create(user, new CreatePersonaRequest(
                "Name", null, null, null, null, "Voice.", tooLong, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsExampleCopyOverMaximumLength() {
        String tooLong = "e".repeat(2001);
        assertThatThrownBy(() -> service.create(user, new CreatePersonaRequest(
                "Name", null, null, null, null, "Voice.", null, null, null, tooLong)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listsPersonasNewestFirstForCurrentWorkspace() {
        Persona a = persona("A");
        Persona b = persona("B");
        when(personas.findByWorkspaceOrderByCreatedAtDesc(workspace)).thenReturn(List.of(b, a));

        List<PersonaSummary> list = service.list(user);

        assertThat(list).extracting(PersonaSummary::name).containsExactly("B", "A");
    }

    @Test
    void getForRejectsPersonaFromAnotherWorkspace() {
        UUID otherId = UUID.randomUUID();
        when(personas.findByWorkspaceAndId(workspace, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFor(user, otherId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updatesEditableFieldsWhileKeepingStatus() {
        Persona existing = persona("Original");
        existing.archive(NOW);
        when(personas.findByWorkspaceAndIdForUpdate(workspace, existing.getId())).thenReturn(Optional.of(existing));

        PersonaSummary updated = service.update(user, existing.getId(), new UpdatePersonaRequest(
                "Renamed", "new desc", SuggestionLanguage.ENGLISH, SuggestionTone.CASUAL, "New audience", "New voice.",
                null, null, null, null));

        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.voiceDescription()).isEqualTo("New voice.");
        assertThat(updated.status()).isEqualTo(PersonaStatus.ARCHIVED);
    }

    @Test
    void archivesActivePersona() {
        Persona existing = persona("Original");
        when(personas.findByWorkspaceAndIdForUpdate(workspace, existing.getId())).thenReturn(Optional.of(existing));

        PersonaSummary archived = service.archive(user, existing.getId());

        assertThat(archived.status()).isEqualTo(PersonaStatus.ARCHIVED);
    }

    @Test
    void restoresArchivedPersona() {
        Persona existing = persona("Original");
        existing.archive(NOW);
        when(personas.findByWorkspaceAndIdForUpdate(workspace, existing.getId())).thenReturn(Optional.of(existing));

        PersonaSummary restored = service.restore(user, existing.getId());

        assertThat(restored.status()).isEqualTo(PersonaStatus.ACTIVE);
    }

    @Test
    void archiveRejectsPersonaFromAnotherWorkspace() {
        UUID otherId = UUID.randomUUID();
        when(personas.findByWorkspaceAndIdForUpdate(workspace, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.archive(user, otherId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Persona persona(String name) {
        return new Persona(workspace, name, null, SuggestionLanguage.AUTO, SuggestionTone.NEUTRAL, null, "Voice.", null, null, null, null, owner, NOW);
    }
}
