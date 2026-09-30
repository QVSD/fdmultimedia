package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.CampaignPerformanceModels.EvidenceStatus;
import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.robots.RobotRun;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "campaign_performance_reviews")
public class CampaignPerformanceReview {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id") private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "robot_run_id") private RobotRun robotRun;
    @Column(nullable = false) private int revision;
    @Enumerated(EnumType.STRING) @Column(name = "observation_window", nullable = false) private Window observationWindow;
    @Enumerated(EnumType.STRING) @Column(name = "primary_metric", nullable = false) private Metric primaryMetric;
    @Column(name = "engine_version", nullable = false) private String engineVersion;
    @Column(name = "recommendation_engine_version", nullable = false) private String recommendationEngineVersion;
    @Enumerated(EnumType.STRING) @Column(name = "evidence_status", nullable = false) private EvidenceStatus evidenceStatus;
    @Column(name = "robot_run_status_snapshot", nullable = false) private String robotRunStatusSnapshot;
    @Column(name = "intended_output_count", nullable = false) private int intendedOutputCount;
    @Column(name = "actual_output_count", nullable = false) private int actualOutputCount;
    @Column(name = "published_output_count", nullable = false) private int publishedOutputCount;
    @Column(name = "failed_output_count", nullable = false) private int failedOutputCount;
    @Column(name = "eligible_by_age_count", nullable = false) private int eligibleByAgeCount;
    @Column(name = "analytics_publication_count", nullable = false) private int analyticsPublicationCount;
    @Column(name = "evidence_cutoff_at", nullable = false) private Instant evidenceCutoffAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_user_id") private AppUser createdByUser;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected CampaignPerformanceReview() {}

    public CampaignPerformanceReview(RobotRun run, int revision, Window window, Metric metric,
            String engineVersion, String recommendationEngineVersion, EvidenceStatus status,
            int intended, int actual, int published, int failed, int eligible, int analytics,
            Instant cutoff, AppUser user, Instant now) {
        this.id = UUID.randomUUID(); this.workspace = run.getWorkspace(); this.robotRun = run;
        this.revision = revision; this.observationWindow = window; this.primaryMetric = metric;
        this.engineVersion = engineVersion; this.recommendationEngineVersion = recommendationEngineVersion;
        this.evidenceStatus = status; this.robotRunStatusSnapshot = run.getStatus().name();
        this.intendedOutputCount = intended; this.actualOutputCount = actual;
        this.publishedOutputCount = published; this.failedOutputCount = failed;
        this.eligibleByAgeCount = eligible; this.analyticsPublicationCount = analytics;
        this.evidenceCutoffAt = cutoff; this.createdByUser = user; this.createdAt = now;
    }

    public UUID getId(){return id;} public Workspace getWorkspace(){return workspace;}
    public RobotRun getRobotRun(){return robotRun;} public int getRevision(){return revision;}
    public Window getObservationWindow(){return observationWindow;} public Metric getPrimaryMetric(){return primaryMetric;}
    public String getEngineVersion(){return engineVersion;} public String getRecommendationEngineVersion(){return recommendationEngineVersion;}
    public EvidenceStatus getEvidenceStatus(){return evidenceStatus;} public String getRobotRunStatusSnapshot(){return robotRunStatusSnapshot;}
    public int getIntendedOutputCount(){return intendedOutputCount;} public int getActualOutputCount(){return actualOutputCount;}
    public int getPublishedOutputCount(){return publishedOutputCount;} public int getFailedOutputCount(){return failedOutputCount;}
    public int getEligibleByAgeCount(){return eligibleByAgeCount;} public int getAnalyticsPublicationCount(){return analyticsPublicationCount;}
    public Instant getEvidenceCutoffAt(){return evidenceCutoffAt;} public Instant getCreatedAt(){return createdAt;}
}
