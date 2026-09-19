package com.fdmultimedia.api.personas;

import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A reusable, workspace-scoped editorial identity: structured configuration
 * only (audience/voice/style/avoid/hashtag-guidance/example-copy), never a
 * raw system prompt. A Persona is not a Robot, an AI provider, a raw
 * system prompt, a social account, a Worker, or a ContentDraft — see
 * package-info for the full boundary. {@link #toSnapshot()} is the only way
 * Persona data ever reaches a {@code ContentSuggestion}; the snapshot is
 * copied once at generation time and never re-read afterward.
 */
@Entity
@Table(name = "personas")
public class Persona {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PersonaStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_language", nullable = false)
    private SuggestionLanguage defaultLanguage;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_tone", nullable = false)
    private SuggestionTone defaultTone;

    @Column
    private String audience;

    @Column(name = "voice_description", nullable = false)
    private String voiceDescription;

    @Column(name = "style_guidelines")
    private String styleGuidelines;

    @Column(name = "avoid_guidelines")
    private String avoidGuidelines;

    @Column(name = "hashtag_guidelines")
    private String hashtagGuidelines;

    @Column(name = "example_copy")
    private String exampleCopy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private AppUser createdByUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Persona() {
    }

    public Persona(
            Workspace workspace,
            String name,
            String description,
            SuggestionLanguage defaultLanguage,
            SuggestionTone defaultTone,
            String audience,
            String voiceDescription,
            String styleGuidelines,
            String avoidGuidelines,
            String hashtagGuidelines,
            String exampleCopy,
            AppUser createdByUser,
            Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.name = name;
        this.description = description;
        this.status = PersonaStatus.ACTIVE;
        this.defaultLanguage = defaultLanguage;
        this.defaultTone = defaultTone;
        this.audience = audience;
        this.voiceDescription = voiceDescription;
        this.styleGuidelines = styleGuidelines;
        this.avoidGuidelines = avoidGuidelines;
        this.hashtagGuidelines = hashtagGuidelines;
        this.exampleCopy = exampleCopy;
        this.createdByUser = createdByUser;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    void prePersist() {
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = timestamp;
        }
        if (updatedAt == null) {
            updatedAt = timestamp;
        }
    }

    /** Editing is allowed regardless of status; only future generations ever see the new values — see {@link #toSnapshot()}. */
    public void update(
            String name,
            String description,
            SuggestionLanguage defaultLanguage,
            SuggestionTone defaultTone,
            String audience,
            String voiceDescription,
            String styleGuidelines,
            String avoidGuidelines,
            String hashtagGuidelines,
            String exampleCopy,
            Instant now) {
        this.name = name;
        this.description = description;
        this.defaultLanguage = defaultLanguage;
        this.defaultTone = defaultTone;
        this.audience = audience;
        this.voiceDescription = voiceDescription;
        this.styleGuidelines = styleGuidelines;
        this.avoidGuidelines = avoidGuidelines;
        this.hashtagGuidelines = hashtagGuidelines;
        this.exampleCopy = exampleCopy;
        this.updatedAt = now;
    }

    /** Archiving only prevents future selection for new generation (see ContentSuggestionService.create); it never touches existing suggestions. */
    public void archive(Instant now) {
        this.status = PersonaStatus.ARCHIVED;
        this.updatedAt = now;
    }

    public void restore(Instant now) {
        this.status = PersonaStatus.ACTIVE;
        this.updatedAt = now;
    }

    public PersonaSnapshot toSnapshot() {
        return new PersonaSnapshot(id, name, audience, voiceDescription, styleGuidelines, avoidGuidelines, hashtagGuidelines, exampleCopy);
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public PersonaStatus getStatus() { return status; }
    public SuggestionLanguage getDefaultLanguage() { return defaultLanguage; }
    public SuggestionTone getDefaultTone() { return defaultTone; }
    public String getAudience() { return audience; }
    public String getVoiceDescription() { return voiceDescription; }
    public String getStyleGuidelines() { return styleGuidelines; }
    public String getAvoidGuidelines() { return avoidGuidelines; }
    public String getHashtagGuidelines() { return hashtagGuidelines; }
    public String getExampleCopy() { return exampleCopy; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
