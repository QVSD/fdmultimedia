package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.CampaignPerformanceModels.OutputEvidenceStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "campaign_performance_review_outputs")
public class CampaignPerformanceReviewOutput {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "review_id") private CampaignPerformanceReview review;
    @Column(name="robot_run_output_id") private UUID robotRunOutputId;
    @Column(name="selection_order") private Integer selectionOrder;
    @Column(name="source_rank") private Integer sourceRank;
    @Column(name="output_status") private String outputStatus;
    @Column(name="campaign_role") private String campaignRole;
    @Column(name="highlight_candidate_id") private UUID highlightCandidateId;
    @Column(name="campaign_plan_id") private UUID campaignPlanId;
    @Column(name="campaign_plan_revision") private Integer campaignPlanRevision;
    @Column(name="campaign_plan_item_id") private UUID campaignPlanItemId;
    @Column(name="campaign_copy_set_id") private UUID campaignCopySetId;
    @Column(name="campaign_copy_set_revision") private Integer campaignCopySetRevision;
    @Column(name="campaign_copy_item_id") private UUID campaignCopyItemId;
    @Column(name="content_suggestion_id") private UUID contentSuggestionId;
    @Column(name="content_draft_id") private UUID contentDraftId;
    @Column(name="publish_schedule_id") private UUID publishScheduleId;
    @Column(name="publication_id") private UUID publicationId;
    private String provider;
    @Column(name="published_at") private Instant publishedAt;
    @Column(name="analytics_snapshot_id") private UUID analyticsSnapshotId;
    @Enumerated(EnumType.STRING) @Column(name="evidence_status", nullable=false) private OutputEvidenceStatus evidenceStatus;
    private Long views; private Long reach; private Long likes; private Long comments; private Long shares; private Long saves;
    @Column(name="total_interactions") private Long totalInteractions;
    @Column(name="created_at", nullable=false) private Instant createdAt;

    protected CampaignPerformanceReviewOutput() {}

    public CampaignPerformanceReviewOutput(CampaignPerformanceReview review, CampaignPerformanceStore.EvidenceRow row, Instant now) {
        id=UUID.randomUUID(); this.review=review; robotRunOutputId=row.robotRunOutputId(); selectionOrder=row.selectionOrder();
        sourceRank=row.sourceRank(); outputStatus=row.outputStatus(); campaignRole=row.campaignRole();
        highlightCandidateId=row.highlightCandidateId(); campaignPlanId=row.campaignPlanId(); campaignPlanRevision=row.campaignPlanRevision();
        campaignPlanItemId=row.campaignPlanItemId(); campaignCopySetId=row.campaignCopySetId();
        campaignCopySetRevision=row.campaignCopySetRevision(); campaignCopyItemId=row.campaignCopyItemId();
        contentSuggestionId=row.contentSuggestionId(); contentDraftId=row.contentDraftId(); publishScheduleId=row.publishScheduleId();
        publicationId=row.publicationId(); provider=row.provider(); publishedAt=row.publishedAt(); analyticsSnapshotId=row.analyticsSnapshotId();
        evidenceStatus=row.evidenceStatus(); views=row.views(); reach=row.reach(); likes=row.likes(); comments=row.comments();
        shares=row.shares(); saves=row.saves(); totalInteractions=row.totalInteractions(); createdAt=now;
    }

    public UUID getId(){return id;} public UUID getRobotRunOutputId(){return robotRunOutputId;}
    public Integer getSelectionOrder(){return selectionOrder;} public Integer getSourceRank(){return sourceRank;}
    public String getOutputStatus(){return outputStatus;} public String getCampaignRole(){return campaignRole;}
    public UUID getHighlightCandidateId(){return highlightCandidateId;} public UUID getCampaignPlanId(){return campaignPlanId;}
    public Integer getCampaignPlanRevision(){return campaignPlanRevision;} public UUID getCampaignPlanItemId(){return campaignPlanItemId;}
    public UUID getCampaignCopySetId(){return campaignCopySetId;} public Integer getCampaignCopySetRevision(){return campaignCopySetRevision;}
    public UUID getCampaignCopyItemId(){return campaignCopyItemId;} public UUID getContentSuggestionId(){return contentSuggestionId;}
    public UUID getContentDraftId(){return contentDraftId;} public UUID getPublishScheduleId(){return publishScheduleId;}
    public UUID getPublicationId(){return publicationId;} public String getProvider(){return provider;} public Instant getPublishedAt(){return publishedAt;}
    public UUID getAnalyticsSnapshotId(){return analyticsSnapshotId;} public OutputEvidenceStatus getEvidenceStatus(){return evidenceStatus;}
    public Long getViews(){return views;} public Long getReach(){return reach;} public Long getLikes(){return likes;}
    public Long getComments(){return comments;} public Long getShares(){return shares;} public Long getSaves(){return saves;}
    public Long getTotalInteractions(){return totalInteractions;}
}
