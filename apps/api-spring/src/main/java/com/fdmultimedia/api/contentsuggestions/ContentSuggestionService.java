package com.fdmultimedia.api.contentsuggestions;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.contentdrafts.ContentDraftRepository;
import com.fdmultimedia.api.contentdrafts.ContentDraftStatus;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobCreateRequest;
import com.fdmultimedia.api.jobs.JobService;
import com.fdmultimedia.api.jobs.JobStatus;
import com.fdmultimedia.api.jobs.JobSummary;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.personas.PersonaSnapshot;
import com.fdmultimedia.api.personas.PersonaStatus;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContentSuggestionService {

    private final AuthService authService;
    private final ContentSuggestionRepository suggestions;
    private final ContentDraftRepository drafts;
    private final ContentEnrichmentContextBuilder contextBuilder;
    private final SocialCopyPromptBuilder promptBuilder;
    private final ContentAiProperties properties;
    private final JobService jobService;
    private final PersonaRepository personas;
    private final Clock clock;

    public ContentSuggestionService(
            AuthService authService,
            ContentSuggestionRepository suggestions,
            ContentDraftRepository drafts,
            ContentEnrichmentContextBuilder contextBuilder,
            SocialCopyPromptBuilder promptBuilder,
            ContentAiProperties properties,
            JobService jobService,
            PersonaRepository personas,
            Clock clock) {
        this.authService = authService;
        this.suggestions = suggestions;
        this.drafts = drafts;
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
        this.properties = properties;
        this.jobService = jobService;
        this.personas = personas;
        this.clock = clock;
    }

    @Transactional
    public ContentSuggestionSummary create(AuthenticatedUser principal, UUID draftId, CreateContentSuggestionRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI_DISABLED");
        }
        ContentDraft draft = drafts.findByWorkspaceAndId(workspace, draftId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content draft not found"));
        if (draft.getStatus() != ContentDraftStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft must be READY to generate a suggestion");
        }
        Persona persona = resolvePersona(workspace, request.personaId());
        SuggestionLanguage language = request.language() != null ? request.language()
                : (persona != null ? persona.getDefaultLanguage() : SuggestionLanguage.AUTO);
        SuggestionTone tone = request.tone() != null ? request.tone()
                : (persona != null ? persona.getDefaultTone() : SuggestionTone.NEUTRAL);
        PersonaSnapshot personaSnapshot = persona == null ? null : persona.toSnapshot();

        String provider = properties.getProvider();
        String model = properties.getModel();
        ContentEnrichmentContext context = contextBuilder.build(draft);
        String prompt = promptBuilder.build(context, language, tone, personaSnapshot);
        if (prompt.length() > properties.getMaxPromptCharacters()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI_CONTEXT_UNAVAILABLE");
        }
        String fingerprint = fingerprint(draft, language, tone, provider, model, SocialCopyPromptBuilder.VERSION_V2, personaSnapshot);

        Instant now = Instant.now(clock);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draftId", draft.getId().toString());
        JobSummary jobSummary = jobService.createForWorkspace(workspace, new JobCreateRequest(JobType.GENERATE_SOCIAL_COPY, payload));
        Job job = jobService.getJobEntityForWorkspace(workspace, jobSummary.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Generation job was not created"));

        ContentSuggestion suggestion = new ContentSuggestion(
                workspace, draft, job, provider, model, SocialCopyPromptBuilder.VERSION_V2,
                language, tone, prompt, fingerprint,
                context.transcriptUsed(), context.transcriptId(), personaSnapshot, membership.getUser(), now);
        return toSummary(suggestions.save(suggestion), draft);
    }

    /** Cross-workspace personaId resolves as not-found, never leaking whether the id exists elsewhere. Archived Personas cannot start a new generation. */
    private Persona resolvePersona(Workspace workspace, UUID personaId) {
        if (personaId == null) {
            return null;
        }
        Persona persona = personas.findByWorkspaceAndId(workspace, personaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Persona not found"));
        if (persona.getStatus() != PersonaStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PERSONA_ARCHIVED");
        }
        return persona;
    }

    @Transactional(readOnly = true)
    public List<ContentSuggestionSummary> listFor(AuthenticatedUser principal, UUID draftId) {
        Workspace workspace = currentWorkspace(principal);
        ContentDraft draft = drafts.findByWorkspaceAndId(workspace, draftId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content draft not found"));
        return suggestions.findByContentDraftOrderByCreatedAtDesc(draft).stream()
                .map(suggestion -> toSummary(suggestion, draft))
                .toList();
    }

    @Transactional(readOnly = true)
    public ContentSuggestionSummary getFor(AuthenticatedUser principal, UUID suggestionId) {
        Workspace workspace = currentWorkspace(principal);
        ContentSuggestion suggestion = suggestions.findByWorkspaceAndId(workspace, suggestionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content suggestion not found"));
        return toSummary(suggestion, suggestion.getContentDraft());
    }

    /**
     * Transactional and idempotency-safe: locks both the suggestion and the
     * Draft, re-verifies READY status and a fresh fingerprint match before
     * mutating anything, so a Draft change and an Apply can never interleave
     * into an inconsistent state, and a double-click either applies once or
     * returns a clear conflict (already APPLIED) rather than reapplying.
     */
    @Transactional
    public ContentSuggestionSummary apply(AuthenticatedUser principal, UUID suggestionId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        ContentSuggestion suggestion = suggestions.findByWorkspaceAndIdForUpdate(workspace, suggestionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content suggestion not found"));
        if (suggestion.getStatus() == ContentSuggestionStatus.APPLIED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SUGGESTION_ALREADY_APPLIED");
        }
        if (suggestion.getStatus() != ContentSuggestionStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Suggestion is not ready to apply");
        }
        ContentDraft draft = drafts.findByWorkspaceAndIdForUpdate(workspace, suggestion.getContentDraft().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content draft not found"));
        // Recomputed from the suggestion's OWN stored Persona snapshot, never by re-reading the live Persona —
        // this is what makes a Persona edit/archive unable to ever cause a stale Apply on its own.
        String currentFingerprint = fingerprint(draft, suggestion.getLanguage(), suggestion.getTone(), suggestion.getProvider(),
                suggestion.getModel(), suggestion.getPromptVersion(), suggestion.getPersonaSnapshot());
        if (!currentFingerprint.equals(suggestion.getInputFingerprint())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SUGGESTION_STALE");
        }
        Instant now = Instant.now(clock);
        String composedCaption = composeCaption(suggestion);
        draft.updateEditableFields(draft.getTitle(), composedCaption, now);
        suggestion.markApplied(membership.getUser().getId(), now);
        return toSummary(suggestion, draft);
    }

    @Transactional
    public ContentSuggestionSummary discard(AuthenticatedUser principal, UUID suggestionId) {
        Workspace workspace = currentWorkspace(principal);
        ContentSuggestion suggestion = suggestions.findByWorkspaceAndIdForUpdate(workspace, suggestionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content suggestion not found"));
        try {
            suggestion.discard();
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
        return toSummary(suggestion, suggestion.getContentDraft());
    }

    @Transactional
    public WorkerContentSuggestionAuthorizationResponse authorizeWorkerGeneration(
            WorkerPrincipal principal, UUID jobId, String machineIdentifier) {
        Worker worker = jobService.requireOnlineWorker(principal, machineIdentifier);
        Job job = requireGenerationJob(worker, jobId);
        ContentSuggestion suggestion = requireSuggestionForJob(job);
        Instant now = Instant.now(clock);
        suggestion.markGenerating(now);
        return new WorkerContentSuggestionAuthorizationResponse(
                suggestion.getId(),
                suggestion.getContentDraft().getId(),
                suggestion.getProvider(),
                suggestion.getModel(),
                suggestion.getPromptVersion(),
                suggestion.getPromptText(),
                suggestion.getLanguage(),
                suggestion.getTone(),
                properties.getMaxHookLength(),
                properties.getMaxCaptionLength(),
                properties.getMaxHashtags(),
                properties.getMaxHashtagLength(),
                properties.getMaxShortTitleLength());
    }

    /**
     * Authoritative validation: the Worker's parsed provider output is never
     * trusted merely because it arrived over a WorkerToken. Invalid output
     * resolves the Job/suggestion straight to a terminal FAILED with
     * AI_OUTPUT_REJECTED rather than consuming a Job retry attempt — retrying
     * the identical prompt through the same parser will not fix malformed
     * content, unlike a transient connection failure reported via {@code /fail}.
     */
    @Transactional
    public ContentSuggestionSummary completeWorkerGeneration(
            WorkerPrincipal principal, UUID jobId, WorkerContentSuggestionCompletionRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireGenerationJob(worker, jobId);
        ContentSuggestion suggestion = requireSuggestionForJob(job);
        if (!suggestion.getId().equals(request.suggestionId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Suggestion does not match job");
        }
        Instant now = Instant.now(clock);
        ValidatedOutput validated;
        try {
            validated = validateOutput(request);
        } catch (OutputRejectedException ex) {
            jobService.failOwnedJob(job, worker, "AI_OUTPUT_REJECTED", ex.getMessage(), true, now);
            suggestion.markFailed("AI_OUTPUT_REJECTED", ex.getMessage(), now);
            return toSummary(suggestion, suggestion.getContentDraft());
        }
        jobService.completeOwnedJob(job, worker, Map.of("suggestionId", suggestion.getId().toString()), now);
        suggestion.markReady(
                validated.hook(), validated.caption(), validated.hashtags(), validated.shortTitle(),
                request.promptTokens(), request.completionTokens(), request.totalTokens(), request.latencyMs(), now);
        return toSummary(suggestion, suggestion.getContentDraft());
    }

    @Transactional
    public ContentSuggestionSummary failWorkerGeneration(WorkerPrincipal principal, UUID jobId, WorkerContentSuggestionFailureRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireGenerationJob(worker, jobId);
        ContentSuggestion suggestion = requireSuggestionForJob(job);
        if (!suggestion.getId().equals(request.suggestionId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Suggestion does not match job");
        }
        Instant now = Instant.now(clock);
        String message = safeErrorMessage(request.errorMessage());
        boolean terminal = Boolean.TRUE.equals(request.terminal());
        jobService.failOwnedJob(job, worker, request.errorCode(), message, terminal, now);
        if (terminal || job.getStatus() == JobStatus.FAILED) {
            suggestion.markFailed(request.errorCode(), message, now);
        } else {
            suggestion.markPendingForRetry(now);
        }
        return toSummary(suggestion, suggestion.getContentDraft());
    }

    private record ValidatedOutput(String hook, String caption, List<String> hashtags, String shortTitle) {
    }

    private static final class OutputRejectedException extends RuntimeException {
        OutputRejectedException(String message) {
            super(message);
        }
    }

    private ValidatedOutput validateOutput(WorkerContentSuggestionCompletionRequest request) {
        String hook = requireBounded(request.hook(), "hook", 1, properties.getMaxHookLength());
        String caption = requireBounded(request.caption(), "caption", 1, properties.getMaxCaptionLength());
        String shortTitle = normalizeOptional(request.shortTitle(), properties.getMaxShortTitleLength());
        List<String> hashtags = normalizeHashtags(request.hashtags());
        requireNonNegative(request.promptTokens(), "promptTokens");
        requireNonNegative(request.completionTokens(), "completionTokens");
        requireNonNegative(request.totalTokens(), "totalTokens");
        if (request.latencyMs() != null && request.latencyMs() < 0) {
            throw new OutputRejectedException("latencyMs must not be negative");
        }
        return new ValidatedOutput(hook, caption, hashtags, shortTitle);
    }

    private String requireBounded(String value, String field, int minLength, int maxLength) {
        if (value == null) {
            throw new OutputRejectedException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() < minLength || trimmed.length() > maxLength) {
            throw new OutputRejectedException(field + " length must be between " + minLength + " and " + maxLength);
        }
        return trimmed;
    }

    private String normalizeOptional(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new OutputRejectedException("shortTitle length must be at most " + maxLength);
        }
        return trimmed;
    }

    /** Trims, strips a leading '#', lowercases, and de-duplicates case-insensitively — hashtags are stored bare (no '#') and lowercase. */
    private List<String> normalizeHashtags(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > properties.getMaxHashtags() * 4) {
            throw new OutputRejectedException("Too many hashtags returned");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String candidate : raw) {
            if (candidate == null) {
                continue;
            }
            String trimmed = candidate.trim();
            String stripped = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
            if (stripped.isBlank()) {
                continue;
            }
            if (containsWhitespace(stripped)) {
                throw new OutputRejectedException("hashtag must not contain whitespace: " + stripped);
            }
            String lower = stripped.toLowerCase(Locale.ROOT);
            if (lower.length() > properties.getMaxHashtagLength()) {
                throw new OutputRejectedException("hashtag exceeds maximum length: " + lower);
            }
            normalized.add(lower);
        }
        if (normalized.size() > properties.getMaxHashtags()) {
            throw new OutputRejectedException("Too many distinct hashtags returned");
        }
        return List.copyOf(normalized);
    }

    private boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private void requireNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new OutputRejectedException(field + " must not be negative");
        }
    }

    /** Preferred deterministic Apply formatting: hook, then caption, then hashtags — never touches Draft.title. */
    private String composeCaption(ContentSuggestion suggestion) {
        StringBuilder builder = new StringBuilder();
        if (suggestion.getHook() != null && !suggestion.getHook().isBlank()) {
            builder.append(suggestion.getHook().trim()).append("\n\n");
        }
        builder.append(suggestion.getCaption() == null ? "" : suggestion.getCaption().trim());
        if (!suggestion.getHashtags().isEmpty()) {
            builder.append("\n\n");
            for (int i = 0; i < suggestion.getHashtags().size(); i++) {
                if (i > 0) {
                    builder.append(' ');
                }
                builder.append('#').append(suggestion.getHashtags().get(i));
            }
        }
        return builder.toString();
    }

    private Job requireGenerationJob(Worker worker, UUID jobId) {
        Job job = jobService.requireJobForWorkerWorkspace(worker, jobId);
        if (job.getType() != JobType.GENERATE_SOCIAL_COPY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not a content suggestion generation");
        }
        if (job.getStatus() != JobStatus.RUNNING && job.getStatus() != JobStatus.ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not active");
        }
        return job;
    }

    private ContentSuggestion requireSuggestionForJob(Job job) {
        return suggestions.findByGenerationJob(job)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content suggestion not found"));
    }

    /**
     * Deterministic hash over exactly the mutable/identity inputs that
     * matter to generated copy: the Draft's current title/caption (the only
     * fields that can change after generation), its immutable source/
     * candidate identity, and the generation configuration. Never a mutable
     * bookkeeping timestamp like {@code updatedAt}, which also changes for
     * unrelated reasons (e.g. publishing state).
     *
     * <p>Branches on {@code promptVersion}: a V1 row's fingerprint is
     * recomputed with the exact original (pre-Persona) formula — the first
     * element used to be the hardcoded {@code SocialCopyPromptBuilder.VERSION}
     * literal, which is byte-identical to passing a V1 row's own
     * {@code promptVersion} here, so this generalization changes nothing for
     * historical rows. Only V2 rows fold in the Persona snapshot — and only
     * the snapshot already stored ON the suggestion itself, never a fresh
     * lookup of the live Persona, which is what keeps a Persona edit/archive
     * from ever retroactively changing a V1 or an already-generated V2
     * suggestion's fingerprint.
     */
    private String fingerprint(
            ContentDraft draft, SuggestionLanguage language, SuggestionTone tone, String provider, String model,
            String promptVersion, PersonaSnapshot personaSnapshot) {
        List<String> parts = new ArrayList<>(List.of(
                promptVersion,
                draft.getId().toString(),
                safe(draft.getTitle()),
                safe(draft.getCaption()),
                draft.getSourceAsset().getId().toString(),
                draft.getSourceHighlightCandidate() == null ? "" : draft.getSourceHighlightCandidate().getId().toString(),
                language.name(),
                tone.name(),
                provider,
                model));
        if (SocialCopyPromptBuilder.VERSION_V2.equals(promptVersion)) {
            parts.add(personaSnapshot == null ? "" : personaSnapshot.personaId().toString());
            parts.add(personaSnapshot == null ? "" : safe(personaSnapshot.audience()));
            parts.add(personaSnapshot == null ? "" : safe(personaSnapshot.voiceDescription()));
            parts.add(personaSnapshot == null ? "" : safe(personaSnapshot.styleGuidelines()));
            parts.add(personaSnapshot == null ? "" : safe(personaSnapshot.avoidGuidelines()));
            parts.add(personaSnapshot == null ? "" : safe(personaSnapshot.hashtagGuidelines()));
            parts.add(personaSnapshot == null ? "" : safe(personaSnapshot.exampleCopy()));
        }
        return sha256Hex(String.join("|", parts));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private String safeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Content suggestion generation failed";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private ContentSuggestionSummary toSummary(ContentSuggestion suggestion, ContentDraft draft) {
        boolean stale = suggestion.getStatus() == ContentSuggestionStatus.READY
                && !fingerprint(draft, suggestion.getLanguage(), suggestion.getTone(), suggestion.getProvider(), suggestion.getModel(),
                        suggestion.getPromptVersion(), suggestion.getPersonaSnapshot())
                        .equals(suggestion.getInputFingerprint());
        return new ContentSuggestionSummary(
                suggestion.getId(),
                suggestion.getContentDraft().getId(),
                suggestion.getRobotRunId(),
                suggestion.getType(),
                suggestion.getStatus(),
                suggestion.getProvider(),
                suggestion.getModel(),
                suggestion.getPromptVersion(),
                suggestion.getLanguage(),
                suggestion.getTone(),
                suggestion.getPersonaId(),
                suggestion.getPersonaName(),
                suggestion.getHook(),
                suggestion.getCaption(),
                suggestion.getHashtags(),
                suggestion.getShortTitle(),
                suggestion.isTranscriptUsed(),
                suggestion.getTranscriptId(),
                suggestion.getPromptTokens(),
                suggestion.getCompletionTokens(),
                suggestion.getTotalTokens(),
                suggestion.getLatencyMs(),
                suggestion.getFailureCode(),
                suggestion.getFailureMessage(),
                stale,
                suggestion.getCreatedAt(),
                suggestion.getCompletedAt(),
                suggestion.getAppliedAt(),
                suggestion.getAppliedByUserId());
    }
}
