package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "highlight_candidates")
public class HighlightCandidate {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false)
    private HighlightAnalysis analysis;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private MediaAsset asset;

    @Column(name = "start_ms", nullable = false)
    private long startMs;

    @Column(name = "end_ms", nullable = false)
    private long endMs;

    @Column(nullable = false)
    private BigDecimal score;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private int rank;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // V2 (DETERMINISTIC_V2) decomposable evidence. All null for pre-V2 candidates,
    // and scene/audio remain null even for V2 until a scene/silence signal pipeline exists.
    @Column(name = "hook_score")
    private BigDecimal hookScore;
    @Column(name = "completeness_score")
    private BigDecimal completenessScore;
    @Column(name = "information_density_score")
    private BigDecimal informationDensityScore;
    @Column(name = "speech_density_score")
    private BigDecimal speechDensityScore;
    @Column(name = "boundary_score")
    private BigDecimal boundaryScore;
    @Column(name = "coverage_score")
    private BigDecimal coverageScore;
    @Column(name = "scene_score")
    private BigDecimal sceneScore;
    @Column(name = "audio_boundary_score")
    private BigDecimal audioBoundaryScore;
    @Column(name = "repetition_penalty")
    private BigDecimal repetitionPenalty;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explanation_labels", columnDefinition = "jsonb")
    private List<String> explanationLabels;

    @Column(name = "transcript_excerpt")
    private String transcriptExcerpt;

    protected HighlightCandidate() {
    }

    public HighlightCandidate(HighlightAnalysis analysis, long startMs, long endMs, BigDecimal score, String reason, int rank, Instant now) {
        this(analysis, startMs, endMs, score, reason, rank, null, now);
    }

    public HighlightCandidate(HighlightAnalysis analysis, long startMs, long endMs, BigDecimal score, String reason, int rank,
            HighlightCandidateEvidence evidence, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = analysis.getWorkspace();
        this.analysis = analysis;
        this.asset = analysis.getAsset();
        this.startMs = startMs;
        this.endMs = endMs;
        this.score = score;
        this.reason = reason;
        this.rank = rank;
        this.createdAt = now;
        if (evidence != null) {
            this.hookScore = evidence.hookScore();
            this.completenessScore = evidence.completenessScore();
            this.informationDensityScore = evidence.informationDensityScore();
            this.speechDensityScore = evidence.speechDensityScore();
            this.boundaryScore = evidence.boundaryScore();
            this.coverageScore = evidence.coverageScore();
            this.sceneScore = evidence.sceneScore();
            this.audioBoundaryScore = evidence.audioBoundaryScore();
            this.repetitionPenalty = evidence.repetitionPenalty();
            this.explanationLabels = evidence.explanationLabels() == null ? null : List.copyOf(evidence.explanationLabels());
            this.transcriptExcerpt = evidence.transcriptExcerpt();
        }
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

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public HighlightAnalysis getAnalysis() { return analysis; }
    public MediaAsset getAsset() { return asset; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public BigDecimal getScore() { return score; }
    public String getReason() { return reason; }
    public int getRank() { return rank; }
    public Instant getCreatedAt() { return createdAt; }
    public BigDecimal getHookScore() { return hookScore; }
    public BigDecimal getCompletenessScore() { return completenessScore; }
    public BigDecimal getInformationDensityScore() { return informationDensityScore; }
    public BigDecimal getSpeechDensityScore() { return speechDensityScore; }
    public BigDecimal getBoundaryScore() { return boundaryScore; }
    public BigDecimal getCoverageScore() { return coverageScore; }
    public BigDecimal getSceneScore() { return sceneScore; }
    public BigDecimal getAudioBoundaryScore() { return audioBoundaryScore; }
    public BigDecimal getRepetitionPenalty() { return repetitionPenalty; }
    public List<String> getExplanationLabels() { return explanationLabels == null ? null : List.copyOf(explanationLabels); }
    public String getTranscriptExcerpt() { return transcriptExcerpt; }

    /** Decomposable V2 scoring evidence for one candidate; every field is optional so this doubles as the "no evidence" case for non-V2 analyzers. */
    public record HighlightCandidateEvidence(
            BigDecimal hookScore,
            BigDecimal completenessScore,
            BigDecimal informationDensityScore,
            BigDecimal speechDensityScore,
            BigDecimal boundaryScore,
            BigDecimal coverageScore,
            BigDecimal sceneScore,
            BigDecimal audioBoundaryScore,
            BigDecimal repetitionPenalty,
            List<String> explanationLabels,
            String transcriptExcerpt) {
    }
}
