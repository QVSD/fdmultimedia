package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import com.fdmultimedia.api.personas.PersonaSnapshot;
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
 * One of exactly two (A/B) treatment definitions for a PERSONA
 * {@link Experiment}. While the owning Experiment is DRAFT, only
 * {@code personaId}/{@code label} are meaningful and may be edited
 * (item 36) — the Persona treatment snapshot fields stay null until
 * {@link #freeze} is called exactly once, at Experiment activation, mirroring
 * the immutable-copy pattern {@code PersonaSnapshot}/{@code ContentSuggestion}
 * already established in Phase 12B: a Persona edited or archived after
 * activation can never change what this variant's treatment means.
 */
@Entity
@Table(name = "experiment_variants")
public class ExperimentVariant {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experiment_id", nullable = false)
    private Experiment experiment;

    @Enumerated(EnumType.STRING)
    @Column(name = "variant_key", nullable = false)
    private ExperimentVariantKey variantKey;

    @Column(nullable = false)
    private String label;

    @Column(name = "persona_id", nullable = false)
    private UUID personaId;

    @Column(name = "persona_name_snapshot")
    private String personaNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "persona_default_language_snapshot")
    private SuggestionLanguage personaDefaultLanguageSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "persona_default_tone_snapshot")
    private SuggestionTone personaDefaultToneSnapshot;

    @Column(name = "persona_audience_snapshot")
    private String personaAudienceSnapshot;

    @Column(name = "persona_voice_description_snapshot")
    private String personaVoiceDescriptionSnapshot;

    @Column(name = "persona_style_guidelines_snapshot")
    private String personaStyleGuidelinesSnapshot;

    @Column(name = "persona_avoid_guidelines_snapshot")
    private String personaAvoidGuidelinesSnapshot;

    @Column(name = "persona_hashtag_guidelines_snapshot")
    private String personaHashtagGuidelinesSnapshot;

    @Column(name = "persona_example_copy_snapshot")
    private String personaExampleCopySnapshot;

    @Column(name = "allocation_weight", nullable = false)
    private int allocationWeight;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExperimentVariant() {
    }

    public ExperimentVariant(Experiment experiment, ExperimentVariantKey variantKey, String label, UUID personaId, Instant now) {
        this.id = UUID.randomUUID();
        this.experiment = experiment;
        this.variantKey = variantKey;
        this.label = label;
        this.personaId = personaId;
        this.allocationWeight = 1;
        this.createdAt = now;
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

    /** DRAFT-only mutation (enforced by the caller checking Experiment.status) — never touches the frozen snapshot fields. */
    public void updateDraftPersona(UUID personaId, String label) {
        this.personaId = personaId;
        this.label = label;
    }

    /** Called exactly once, by {@code ExperimentService.activate}, under the Experiment's own row lock. */
    public void freeze(PersonaSnapshot snapshot, SuggestionLanguage defaultLanguage, SuggestionTone defaultTone) {
        this.personaNameSnapshot = snapshot.personaName();
        this.personaDefaultLanguageSnapshot = defaultLanguage;
        this.personaDefaultToneSnapshot = defaultTone;
        this.personaAudienceSnapshot = snapshot.audience();
        this.personaVoiceDescriptionSnapshot = snapshot.voiceDescription();
        this.personaStyleGuidelinesSnapshot = snapshot.styleGuidelines();
        this.personaAvoidGuidelinesSnapshot = snapshot.avoidGuidelines();
        this.personaHashtagGuidelinesSnapshot = snapshot.hashtagGuidelines();
        this.personaExampleCopySnapshot = snapshot.exampleCopy();
    }

    public boolean isFrozen() {
        return personaNameSnapshot != null;
    }

    /** Reconstructed from this variant's own frozen columns only — never re-reads the live Persona row. */
    public PersonaSnapshot toPersonaSnapshot() {
        return new PersonaSnapshot(personaId, personaNameSnapshot, personaAudienceSnapshot, personaVoiceDescriptionSnapshot,
                personaStyleGuidelinesSnapshot, personaAvoidGuidelinesSnapshot, personaHashtagGuidelinesSnapshot, personaExampleCopySnapshot);
    }

    public UUID getId() { return id; }
    public Experiment getExperiment() { return experiment; }
    public ExperimentVariantKey getVariantKey() { return variantKey; }
    public String getLabel() { return label; }
    public UUID getPersonaId() { return personaId; }
    public String getPersonaNameSnapshot() { return personaNameSnapshot; }
    public SuggestionLanguage getPersonaDefaultLanguageSnapshot() { return personaDefaultLanguageSnapshot; }
    public SuggestionTone getPersonaDefaultToneSnapshot() { return personaDefaultToneSnapshot; }
    public String getPersonaAudienceSnapshot() { return personaAudienceSnapshot; }
    public String getPersonaVoiceDescriptionSnapshot() { return personaVoiceDescriptionSnapshot; }
    public String getPersonaStyleGuidelinesSnapshot() { return personaStyleGuidelinesSnapshot; }
    public String getPersonaAvoidGuidelinesSnapshot() { return personaAvoidGuidelinesSnapshot; }
    public String getPersonaHashtagGuidelinesSnapshot() { return personaHashtagGuidelinesSnapshot; }
    public String getPersonaExampleCopySnapshot() { return personaExampleCopySnapshot; }
    public int getAllocationWeight() { return allocationWeight; }
    public Instant getCreatedAt() { return createdAt; }
}
