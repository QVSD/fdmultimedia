package com.fdmultimedia.api.transcripts;

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
import java.util.UUID;

@Entity
@Table(name = "transcript_segments")
public class TranscriptSegment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transcript_id", nullable = false)
    private MediaTranscript transcript;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "start_ms", nullable = false)
    private long startMs;

    @Column(name = "end_ms", nullable = false)
    private long endMs;

    @Column(nullable = false)
    private String text;

    private BigDecimal confidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TranscriptSegment() {
    }

    public TranscriptSegment(MediaTranscript transcript, int sequence, long startMs, long endMs, String text, BigDecimal confidence, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = transcript.getWorkspace();
        this.transcript = transcript;
        this.sequence = sequence;
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.confidence = confidence;
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

    public UUID getId() { return id; }
    public MediaTranscript getTranscript() { return transcript; }
    public int getSequence() { return sequence; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public String getText() { return text; }
    public BigDecimal getConfidence() { return confidence; }
    public Instant getCreatedAt() { return createdAt; }
}
