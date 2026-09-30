package com.fdmultimedia.api.campaigns;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Exactly one item per {@code RobotRunOutput} within one copy set revision
 * (item 5) — coordinated copy only, never itself the applied Draft caption
 * until {@link CampaignCopySetService#apply} materializes a normal {@code
 * ContentSuggestion} from it. {@code sequence} always mirrors that output's
 * frozen {@code selectionOrder}; the provider's response order is never
 * authoritative (item 13). {@code campaignContentPlanItemId} freezes exactly
 * which plan item's role/guidance this copy item was coordinated under, for
 * mapping clarity (item 70) — never re-read live.
 */
@Entity
@Table(name = "campaign_copy_items")
public class CampaignCopyItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "copy_set_id", nullable = false)
    private CampaignCopySet copySet;

    @Column(name = "robot_run_output_id", nullable = false)
    private UUID robotRunOutputId;

    @Column(name = "campaign_content_plan_item_id", nullable = false)
    private UUID campaignContentPlanItemId;

    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private String hook;

    @Column(nullable = false)
    private String caption;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> hashtags = new ArrayList<>();

    @Column(name = "short_title")
    private String shortTitle;

    @Column(name = "continuity_note")
    private String continuityNote;

    /** Set once, at {@link CampaignCopySetService#apply} time, when this item's content materializes into a normal ContentSuggestion. */
    @Column(name = "content_suggestion_id")
    private UUID contentSuggestionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CampaignCopyItem() {
    }

    public CampaignCopyItem(CampaignCopySet copySet, UUID robotRunOutputId, UUID campaignContentPlanItemId, int sequence,
            String hook, String caption, List<String> hashtags, String shortTitle, String continuityNote, Instant now) {
        this.id = UUID.randomUUID();
        this.copySet = copySet;
        this.robotRunOutputId = robotRunOutputId;
        this.campaignContentPlanItemId = campaignContentPlanItemId;
        this.sequence = sequence;
        this.hook = hook;
        this.caption = caption;
        this.hashtags = hashtags == null ? new ArrayList<>() : new ArrayList<>(hashtags);
        this.shortTitle = shortTitle;
        this.continuityNote = continuityNote;
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

    public void bindContentSuggestion(UUID contentSuggestionId) {
        this.contentSuggestionId = contentSuggestionId;
    }

    public UUID getId() { return id; }
    public CampaignCopySet getCopySet() { return copySet; }
    public UUID getRobotRunOutputId() { return robotRunOutputId; }
    public UUID getCampaignContentPlanItemId() { return campaignContentPlanItemId; }
    public int getSequence() { return sequence; }
    public String getHook() { return hook; }
    public String getCaption() { return caption; }
    public List<String> getHashtags() { return List.copyOf(hashtags); }
    public String getShortTitle() { return shortTitle; }
    public String getContinuityNote() { return continuityNote; }
    public UUID getContentSuggestionId() { return contentSuggestionId; }
    public Instant getCreatedAt() { return createdAt; }
}
