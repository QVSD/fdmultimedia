package com.fdmultimedia.api.personas;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Human-facing CRUD for {@link Persona}. Never hard-deletes — see
 * {@link #archive} / {@link #restore}. Field bounds mirror the DB-level
 * CHECK constraints in V21 exactly, enforced here too so a violation is
 * reported as a clean 400 rather than surfacing a raw constraint-violation
 * error from Postgres.
 */
@Service
public class PersonaService {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_AUDIENCE_LENGTH = 500;
    private static final int MAX_VOICE_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_STYLE_GUIDELINES_LENGTH = 2000;
    private static final int MAX_AVOID_GUIDELINES_LENGTH = 2000;
    private static final int MAX_HASHTAG_GUIDELINES_LENGTH = 1000;
    private static final int MAX_EXAMPLE_COPY_LENGTH = 2000;

    private final AuthService authService;
    private final PersonaRepository personas;
    private final Clock clock;

    public PersonaService(AuthService authService, PersonaRepository personas, Clock clock) {
        this.authService = authService;
        this.personas = personas;
        this.clock = clock;
    }

    @Transactional
    public PersonaSummary create(AuthenticatedUser principal, CreatePersonaRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Instant now = Instant.now(clock);
        Persona persona = new Persona(
                membership.getWorkspace(),
                requireBounded(request.name(), "name", MAX_NAME_LENGTH),
                normalizeOptional(request.description(), "description", MAX_DESCRIPTION_LENGTH),
                request.defaultLanguage() != null ? request.defaultLanguage() : SuggestionLanguage.AUTO,
                request.defaultTone() != null ? request.defaultTone() : SuggestionTone.NEUTRAL,
                normalizeOptional(request.audience(), "audience", MAX_AUDIENCE_LENGTH),
                requireBounded(request.voiceDescription(), "voiceDescription", MAX_VOICE_DESCRIPTION_LENGTH),
                normalizeOptional(request.styleGuidelines(), "styleGuidelines", MAX_STYLE_GUIDELINES_LENGTH),
                normalizeOptional(request.avoidGuidelines(), "avoidGuidelines", MAX_AVOID_GUIDELINES_LENGTH),
                normalizeOptional(request.hashtagGuidelines(), "hashtagGuidelines", MAX_HASHTAG_GUIDELINES_LENGTH),
                normalizeOptional(request.exampleCopy(), "exampleCopy", MAX_EXAMPLE_COPY_LENGTH),
                membership.getUser(),
                now);
        return toSummary(personas.save(persona));
    }

    @Transactional(readOnly = true)
    public List<PersonaSummary> list(AuthenticatedUser principal) {
        Workspace workspace = currentWorkspace(principal);
        return personas.findByWorkspaceOrderByCreatedAtDesc(workspace).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public PersonaSummary getFor(AuthenticatedUser principal, UUID personaId) {
        return toSummary(requirePersona(currentWorkspace(principal), personaId));
    }

    @Transactional
    public PersonaSummary update(AuthenticatedUser principal, UUID personaId, UpdatePersonaRequest request) {
        Workspace workspace = currentWorkspace(principal);
        Persona persona = personas.findByWorkspaceAndIdForUpdate(workspace, personaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Persona not found"));
        persona.update(
                requireBounded(request.name(), "name", MAX_NAME_LENGTH),
                normalizeOptional(request.description(), "description", MAX_DESCRIPTION_LENGTH),
                request.defaultLanguage() != null ? request.defaultLanguage() : SuggestionLanguage.AUTO,
                request.defaultTone() != null ? request.defaultTone() : SuggestionTone.NEUTRAL,
                normalizeOptional(request.audience(), "audience", MAX_AUDIENCE_LENGTH),
                requireBounded(request.voiceDescription(), "voiceDescription", MAX_VOICE_DESCRIPTION_LENGTH),
                normalizeOptional(request.styleGuidelines(), "styleGuidelines", MAX_STYLE_GUIDELINES_LENGTH),
                normalizeOptional(request.avoidGuidelines(), "avoidGuidelines", MAX_AVOID_GUIDELINES_LENGTH),
                normalizeOptional(request.hashtagGuidelines(), "hashtagGuidelines", MAX_HASHTAG_GUIDELINES_LENGTH),
                normalizeOptional(request.exampleCopy(), "exampleCopy", MAX_EXAMPLE_COPY_LENGTH),
                Instant.now(clock));
        return toSummary(persona);
    }

    @Transactional
    public PersonaSummary archive(AuthenticatedUser principal, UUID personaId) {
        Workspace workspace = currentWorkspace(principal);
        Persona persona = personas.findByWorkspaceAndIdForUpdate(workspace, personaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Persona not found"));
        persona.archive(Instant.now(clock));
        return toSummary(persona);
    }

    @Transactional
    public PersonaSummary restore(AuthenticatedUser principal, UUID personaId) {
        Workspace workspace = currentWorkspace(principal);
        Persona persona = personas.findByWorkspaceAndIdForUpdate(workspace, personaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Persona not found"));
        persona.restore(Instant.now(clock));
        return toSummary(persona);
    }

    private Persona requirePersona(Workspace workspace, UUID personaId) {
        return personas.findByWorkspaceAndId(workspace, personaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Persona not found"));
    }

    private String requireBounded(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    private String normalizeOptional(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private PersonaSummary toSummary(Persona persona) {
        return new PersonaSummary(
                persona.getId(),
                persona.getName(),
                persona.getDescription(),
                persona.getStatus(),
                persona.getDefaultLanguage(),
                persona.getDefaultTone(),
                persona.getAudience(),
                persona.getVoiceDescription(),
                persona.getStyleGuidelines(),
                persona.getAvoidGuidelines(),
                persona.getHashtagGuidelines(),
                persona.getExampleCopy(),
                persona.getCreatedAt(),
                persona.getUpdatedAt());
    }
}
