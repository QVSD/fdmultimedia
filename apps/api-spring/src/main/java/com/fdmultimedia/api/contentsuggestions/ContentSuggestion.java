package com.fdmultimedia.api.contentsuggestions;

import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.personas.PersonaSnapshot;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A durable, reviewable AI-generated suggestion for one {@link ContentDraft}
 * — never an authoritative mutation of it. Generation output is frozen the
 * instant a Worker's completion is validated; regenerating never overwrites
 * an existing row, it creates a new one. See package-info for the full
 * boundary this entity sits behind.
 */
@Entity
@Table(name = "content_suggestions")
public class ContentSuggestion {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_draft_id", nullable = false)
    private ContentDraft contentDraft;

    /**
     * Explicit provenance (Phase 12C). {@code origin} is never inferred: a
     * human-initiated generation is always MANUAL with a null
     * {@code robotRunId}, regardless of whether the Draft itself happens to
     * carry its own {@code ContentDraft.robotRunId} provenance — those are
     * two different questions ("who made this Draft" vs. "who triggered
     * this suggestion"). {@code robotRunId} is a plain UUID, no
     * relationship, set only by {@link #forRobot}.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentSuggestionOrigin origin;

    @Column(name = "robot_run_id")
    private UUID robotRunId;

    @Column(name = "robot_run_output_id")
    private UUID robotRunOutputId;

    /**
     * Phase 14A provenance (item 29): set only when this suggestion was
     * generated under a frozen experiment treatment (see
     * RobotRunOrchestrator.beginAiGeneration / ExperimentTreatment) — plain
     * UUIDs, no relationship, so this package never depends on
     * {@code com.fdmultimedia.api.experiments}. Used by
     * PublicationAttributionService to detect a protocol deviation when the
     * Publication's final applied suggestion is not this one.
     */
    @Column(name = "experiment_id")
    private UUID experimentId;

    @Column(name = "experiment_assignment_id")
    private UUID experimentAssignmentId;

    @Column(name = "experiment_variant_id")
    private UUID experimentVariantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generation_job_id", nullable = false)
    private Job generationJob;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentSuggestionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentSuggestionStatus status;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SuggestionLanguage language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SuggestionTone tone;

    /** The exact prompt sent to the provider, frozen at generation time. Never exposed via any API DTO or log line. */
    @Column(name = "prompt_text", nullable = false)
    private String promptText;

    @Column
    private String hook;

    @Column
    private String caption;

    @Column(name = "short_title")
    private String shortTitle;

    @ElementCollection
    @CollectionTable(name = "content_suggestion_hashtags", joinColumns = @JoinColumn(name = "content_suggestion_id"))
    @OrderColumn(name = "position")
    @Column(name = "tag", nullable = false)
    private List<String> hashtags = new ArrayList<>();

    @Column(name = "input_fingerprint", nullable = false)
    private String inputFingerprint;

    /**
     * Immutable Persona snapshot fields (Phase 12B), copied once at
     * generation time from {@link PersonaSnapshot} — never re-read from the
     * live {@code Persona} row. All null when no Persona was selected.
     */
    @Column(name = "persona_id")
    private UUID personaId;

    @Column(name = "persona_name")
    private String personaName;

    @Column(name = "persona_audience")
    private String personaAudience;

    @Column(name = "persona_voice_description")
    private String personaVoiceDescription;

    @Column(name = "persona_style_guidelines")
    private String personaStyleGuidelines;

    @Column(name = "persona_avoid_guidelines")
    private String personaAvoidGuidelines;

    @Column(name = "persona_hashtag_guidelines")
    private String personaHashtagGuidelines;

    @Column(name = "persona_example_copy")
    private String personaExampleCopy;

    @Column(name = "transcript_used", nullable = false)
    private boolean transcriptUsed;

    @Column(name = "transcript_id")
    private UUID transcriptId;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private AppUser createdByUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "applied_by_user_id")
    private UUID appliedByUserId;

    protected ContentSuggestion() {
    }

    /** Backward-compatible overload (no Persona) — delegates below with a null snapshot. */
    public ContentSuggestion(
            Workspace workspace,
            ContentDraft contentDraft,
            Job generationJob,
            String provider,
            String model,
            String promptVersion,
            SuggestionLanguage language,
            SuggestionTone tone,
            String promptText,
            String inputFingerprint,
            boolean transcriptUsed,
            UUID transcriptId,
            AppUser createdByUser,
            Instant now) {
        this(workspace, contentDraft, generationJob, provider, model, promptVersion, language, tone, promptText,
                inputFingerprint, transcriptUsed, transcriptId, null, createdByUser, now);
    }

    public ContentSuggestion(
            Workspace workspace,
            ContentDraft contentDraft,
            Job generationJob,
            String provider,
            String model,
            String promptVersion,
            SuggestionLanguage language,
            SuggestionTone tone,
            String promptText,
            String inputFingerprint,
            boolean transcriptUsed,
            UUID transcriptId,
            PersonaSnapshot personaSnapshot,
            AppUser createdByUser,
            Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.contentDraft = contentDraft;
        this.origin = ContentSuggestionOrigin.MANUAL;
        this.robotRunId = null;
        this.generationJob = generationJob;
        this.type = ContentSuggestionType.SOCIAL_COPY;
        this.status = ContentSuggestionStatus.PENDING;
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.language = language;
        this.tone = tone;
        this.promptText = promptText;
        this.inputFingerprint = inputFingerprint;
        this.transcriptUsed = transcriptUsed;
        this.transcriptId = transcriptId;
        if (personaSnapshot != null) {
            this.personaId = personaSnapshot.personaId();
            this.personaName = personaSnapshot.personaName();
            this.personaAudience = personaSnapshot.audience();
            this.personaVoiceDescription = personaSnapshot.voiceDescription();
            this.personaStyleGuidelines = personaSnapshot.styleGuidelines();
            this.personaAvoidGuidelines = personaSnapshot.avoidGuidelines();
            this.personaHashtagGuidelines = personaSnapshot.hashtagGuidelines();
            this.personaExampleCopy = personaSnapshot.exampleCopy();
        }
        this.createdByUser = createdByUser;
        this.createdAt = now;
    }

    /**
     * The only path that produces an {@code origin == ROBOT} suggestion
     * (Phase 12C) — {@code createdByUser} is still the Robot's own
     * {@code createdByUser} (the human who owns it for workspace audit),
     * never a fabricated system actor; {@code origin} is what actually
     * distinguishes this from a human-initiated generation. Called only by
     * {@code ContentSuggestionService.createForRobot}, which is itself only
     * ever invoked by {@code RobotRunOrchestrator} under that run's own row
     * lock — see there for why at most one of these can ever be created per
     * RobotRun.
     */
    public static ContentSuggestion forRobot(
            Workspace workspace,
            ContentDraft contentDraft,
            Job generationJob,
            String provider,
            String model,
            String promptVersion,
            SuggestionLanguage language,
            SuggestionTone tone,
            String promptText,
            String inputFingerprint,
            boolean transcriptUsed,
            UUID transcriptId,
            PersonaSnapshot personaSnapshot,
            UUID robotRunId,
            AppUser createdByUser,
            Instant now,
            UUID experimentId,
            UUID experimentAssignmentId,
            UUID experimentVariantId) {
        return forRobot(workspace, contentDraft, generationJob, provider, model, promptVersion, language, tone,
                promptText, inputFingerprint, transcriptUsed, transcriptId, personaSnapshot, robotRunId, null,
                createdByUser, now, experimentId, experimentAssignmentId, experimentVariantId);
    }

    public static ContentSuggestion forRobot(
            Workspace workspace, ContentDraft contentDraft, Job generationJob, String provider, String model,
            String promptVersion, SuggestionLanguage language, SuggestionTone tone, String promptText,
            String inputFingerprint, boolean transcriptUsed, UUID transcriptId, PersonaSnapshot personaSnapshot,
            UUID robotRunId, UUID robotRunOutputId, AppUser createdByUser, Instant now,
            UUID experimentId, UUID experimentAssignmentId, UUID experimentVariantId) {
        ContentSuggestion suggestion = new ContentSuggestion(
                workspace, contentDraft, generationJob, provider, model, promptVersion, language, tone, promptText,
                inputFingerprint, transcriptUsed, transcriptId, personaSnapshot, createdByUser, now);
        suggestion.origin = ContentSuggestionOrigin.ROBOT;
        suggestion.robotRunId = robotRunId;
        suggestion.robotRunOutputId = robotRunOutputId;
        suggestion.experimentId = experimentId;
        suggestion.experimentAssignmentId = experimentAssignmentId;
        suggestion.experimentVariantId = experimentVariantId;
        return suggestion;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isTerminal() {
        return ContentSuggestionStatus.terminalStatuses().contains(status);
    }

    public void markGenerating(Instant now) {
        if (status != ContentSuggestionStatus.PENDING && status != ContentSuggestionStatus.GENERATING) {
            throw new IllegalStateException("Suggestion is not pending");
        }
        this.status = ContentSuggestionStatus.GENERATING;
    }

    public void markReady(
            String hook,
            String caption,
            List<String> hashtags,
            String shortTitle,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Long latencyMs,
            Instant now) {
        this.status = ContentSuggestionStatus.READY;
        this.hook = hook;
        this.caption = caption;
        this.hashtags = new ArrayList<>(hashtags);
        this.shortTitle = shortTitle;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.latencyMs = latencyMs;
        this.completedAt = now;
    }

    public void markFailed(String failureCode, String failureMessage, Instant now) {
        this.status = ContentSuggestionStatus.FAILED;
        this.failureCode = normalize(failureCode);
        this.failureMessage = normalize(failureMessage);
        this.completedAt = now;
    }

    public void markPendingForRetry(Instant now) {
        this.status = ContentSuggestionStatus.PENDING;
    }

    public void markApplied(UUID appliedByUserId, Instant now) {
        if (status != ContentSuggestionStatus.READY) {
            throw new IllegalStateException("Only a READY suggestion can be applied");
        }
        this.status = ContentSuggestionStatus.APPLIED;
        this.appliedByUserId = appliedByUserId;
        this.appliedAt = now;
    }

    public void discard() {
        if (status != ContentSuggestionStatus.READY) {
            throw new IllegalStateException("Only a READY suggestion can be discarded");
        }
        this.status = ContentSuggestionStatus.DISCARDED;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Reconstructed from this suggestion's own stored columns only — never re-reads the live Persona row. Null when no Persona was selected. */
    public PersonaSnapshot getPersonaSnapshot() {
        if (personaId == null) {
            return null;
        }
        return new PersonaSnapshot(personaId, personaName, personaAudience, personaVoiceDescription,
                personaStyleGuidelines, personaAvoidGuidelines, personaHashtagGuidelines, personaExampleCopy);
    }

    public UUID getId() { return id; }
    public UUID getPersonaId() { return personaId; }
    public String getPersonaName() { return personaName; }
    public Workspace getWorkspace() { return workspace; }
    public ContentDraft getContentDraft() { return contentDraft; }
    public ContentSuggestionOrigin getOrigin() { return origin; }
    public UUID getRobotRunId() { return robotRunId; }
    public UUID getRobotRunOutputId() { return robotRunOutputId; }
    public UUID getExperimentId() { return experimentId; }
    public UUID getExperimentAssignmentId() { return experimentAssignmentId; }
    public UUID getExperimentVariantId() { return experimentVariantId; }
    public Job getGenerationJob() { return generationJob; }
    public ContentSuggestionType getType() { return type; }
    public ContentSuggestionStatus getStatus() { return status; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public SuggestionLanguage getLanguage() { return language; }
    public SuggestionTone getTone() { return tone; }
    public String getPromptText() { return promptText; }
    public String getHook() { return hook; }
    public String getCaption() { return caption; }
    public String getShortTitle() { return shortTitle; }
    public List<String> getHashtags() { return List.copyOf(hashtags); }
    public String getInputFingerprint() { return inputFingerprint; }
    public boolean isTranscriptUsed() { return transcriptUsed; }
    public UUID getTranscriptId() { return transcriptId; }
    public Integer getPromptTokens() { return promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public Long getLatencyMs() { return latencyMs; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getAppliedAt() { return appliedAt; }
    public UUID getAppliedByUserId() { return appliedByUserId; }
}
