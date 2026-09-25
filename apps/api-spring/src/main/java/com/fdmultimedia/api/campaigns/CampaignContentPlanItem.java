package com.fdmultimedia.api.campaigns;

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
 * Exactly one item per {@code RobotRunOutput} within one plan revision (item
 * 70) — guidance only, never a caption. {@code sequence} always mirrors that
 * output's frozen {@code selectionOrder}; V1 never reorders (item 41).
 */
@Entity
@Table(name = "campaign_content_plan_items")
public class CampaignContentPlanItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private CampaignContentPlan plan;

    @Column(name = "robot_run_output_id", nullable = false)
    private UUID robotRunOutputId;

    @Column(nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignPlanRole role;

    @Column(name = "hook_guidance")
    private String hookGuidance;

    @Column(name = "caption_guidance")
    private String captionGuidance;

    @Column(name = "cta_guidance")
    private String ctaGuidance;

    @Column(name = "avoid_repetition_guidance")
    private String avoidRepetitionGuidance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CampaignContentPlanItem() {
    }

    public CampaignContentPlanItem(CampaignContentPlan plan, UUID robotRunOutputId, int sequence, CampaignPlanRole role,
            String hookGuidance, String captionGuidance, String ctaGuidance, String avoidRepetitionGuidance, Instant now) {
        this.id = UUID.randomUUID();
        this.plan = plan;
        this.robotRunOutputId = robotRunOutputId;
        this.sequence = sequence;
        this.role = role;
        this.hookGuidance = hookGuidance;
        this.captionGuidance = captionGuidance;
        this.ctaGuidance = ctaGuidance;
        this.avoidRepetitionGuidance = avoidRepetitionGuidance;
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
    public CampaignContentPlan getPlan() { return plan; }
    public UUID getRobotRunOutputId() { return robotRunOutputId; }
    public int getSequence() { return sequence; }
    public CampaignPlanRole getRole() { return role; }
    public String getHookGuidance() { return hookGuidance; }
    public String getCaptionGuidance() { return captionGuidance; }
    public String getCtaGuidance() { return ctaGuidance; }
    public String getAvoidRepetitionGuidance() { return avoidRepetitionGuidance; }
    public Instant getCreatedAt() { return createdAt; }
}
