package com.fdmultimedia.api.optimization;

import com.fdmultimedia.api.analytics.CampaignPerformanceReview;
import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.Direction;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.Statistic;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.Status;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.Origin;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "optimization_proposals")
public class OptimizationProposal {
    @Id private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="workspace_id") private Workspace workspace;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="source_review_id") private CampaignPerformanceReview sourceReview;
    @Column(nullable=false) private int revision;
    @Column(name="is_current",nullable=false) private boolean current;
    @Column(name="engine_version",nullable=false) private String engineVersion;
    @Column(nullable=false) private String factor;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Status status;
    @Column(name="baseline_persona_id",nullable=false) private UUID baselinePersonaId;
    @Column(name="baseline_persona_name_snapshot",nullable=false) private String baselinePersonaNameSnapshot;
    @Column(name="baseline_persona_fingerprint",nullable=false) private String baselinePersonaFingerprint;
    @Column(name="candidate_persona_id",nullable=false) private UUID candidatePersonaId;
    @Column(name="candidate_persona_name_snapshot",nullable=false) private String candidatePersonaNameSnapshot;
    @Column(name="candidate_persona_fingerprint",nullable=false) private String candidatePersonaFingerprint;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private DashboardQuery.Metric metric;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Statistic statistic;
    @Enumerated(EnumType.STRING) @Column(name="observation_window",nullable=false) private DashboardQuery.Window observationWindow;
    @Column(nullable=false) private String provider;
    @Column(name="cohort_from",nullable=false) private Instant cohortFrom;
    @Column(name="cohort_to",nullable=false) private Instant cohortTo;
    @Column(name="baseline_sample",nullable=false) private int baselineSample;
    @Column(name="candidate_sample",nullable=false) private int candidateSample;
    @Column(name="baseline_eligible",nullable=false) private int baselineEligible;
    @Column(name="candidate_eligible",nullable=false) private int candidateEligible;
    @Column(name="baseline_coverage",nullable=false,precision=7,scale=6) private BigDecimal baselineCoverage;
    @Column(name="candidate_coverage",nullable=false,precision=7,scale=6) private BigDecimal candidateCoverage;
    @Column(name="baseline_value",nullable=false,precision=30,scale=6) private BigDecimal baselineValue;
    @Column(name="candidate_value",nullable=false,precision=30,scale=6) private BigDecimal candidateValue;
    @Column(name="absolute_difference",nullable=false,precision=30,scale=6) private BigDecimal absoluteDifference;
    @Column(name="relative_difference_percent",precision=20,scale=6) private BigDecimal relativeDifferencePercent;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Direction direction;
    @Column(name="evidence_fingerprint",nullable=false) private String evidenceFingerprint;
    @Column(nullable=false) private String rationale;
    @Column(nullable=false) private String limitation;
    @Column(name="materialized_experiment_id") private UUID materializedExperimentId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Origin origin;
    @Column(name="automation_engine_version") private String automationEngineVersion;
    @Column(name="automation_robot_id") private UUID automationRobotId;
    @Column(name="automation_policy_revision") private Integer automationPolicyRevision;
    @Column(name="automation_trigger") private String automationTrigger;
    @Column(name="automation_opportunity_fingerprint") private String automationOpportunityFingerprint;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="created_by_user_id") private AppUser createdByUser;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="reviewed_at") private Instant reviewedAt;
    @Column(name="materialized_at") private Instant materializedAt;

    protected OptimizationProposal() {}
    public OptimizationProposal(Workspace workspace, CampaignPerformanceReview sourceReview, int revision,
            UUID baselinePersonaId, String baselinePersonaName, String baselinePersonaFingerprint,
            UUID candidatePersonaId, String candidatePersonaName, String candidatePersonaFingerprint,
            DashboardQuery.Metric metric, DashboardQuery.Window window, String provider,
            Instant cohortFrom, Instant cohortTo, int baselineSample, int candidateSample,
            int baselineEligible, int candidateEligible, BigDecimal baselineCoverage, BigDecimal candidateCoverage,
            BigDecimal baselineValue, BigDecimal candidateValue, BigDecimal absoluteDifference,
            BigDecimal relativeDifferencePercent, Direction direction, String fingerprint,
            String rationale, String limitation, AppUser createdByUser, Instant now) {
        this(workspace,sourceReview,revision,baselinePersonaId,baselinePersonaName,baselinePersonaFingerprint,
                candidatePersonaId,candidatePersonaName,candidatePersonaFingerprint,metric,window,provider,cohortFrom,
                cohortTo,baselineSample,candidateSample,baselineEligible,candidateEligible,baselineCoverage,
                candidateCoverage,baselineValue,candidateValue,absoluteDifference,relativeDifferencePercent,direction,
                fingerprint,rationale,limitation,createdByUser,now,Origin.MANUAL,null,null,null,null,fingerprint);
    }
    public OptimizationProposal(Workspace workspace, CampaignPerformanceReview sourceReview, int revision,
            UUID baselinePersonaId, String baselinePersonaName, String baselinePersonaFingerprint,
            UUID candidatePersonaId, String candidatePersonaName, String candidatePersonaFingerprint,
            DashboardQuery.Metric metric, DashboardQuery.Window window, String provider,
            Instant cohortFrom, Instant cohortTo, int baselineSample, int candidateSample,
            int baselineEligible, int candidateEligible, BigDecimal baselineCoverage, BigDecimal candidateCoverage,
            BigDecimal baselineValue, BigDecimal candidateValue, BigDecimal absoluteDifference,
            BigDecimal relativeDifferencePercent, Direction direction, String fingerprint,
            String rationale, String limitation, AppUser createdByUser, Instant now, Origin origin,
            String automationEngineVersion, UUID automationRobotId, Integer automationPolicyRevision,
            String automationTrigger, String automationOpportunityFingerprint) {
        this.id=UUID.randomUUID(); this.workspace=workspace; this.sourceReview=sourceReview; this.revision=revision;
        this.current=true; this.engineVersion=OptimizationProposalService.ENGINE_VERSION; this.factor="PERSONA";
        this.status=Status.READY_FOR_REVIEW; this.baselinePersonaId=baselinePersonaId;
        this.baselinePersonaNameSnapshot=baselinePersonaName; this.candidatePersonaId=candidatePersonaId;
        this.baselinePersonaFingerprint=baselinePersonaFingerprint;
        this.candidatePersonaNameSnapshot=candidatePersonaName; this.candidatePersonaFingerprint=candidatePersonaFingerprint;
        this.metric=metric; this.statistic=Statistic.MEDIAN;
        this.observationWindow=window; this.provider=provider; this.cohortFrom=cohortFrom; this.cohortTo=cohortTo;
        this.baselineSample=baselineSample; this.candidateSample=candidateSample;
        this.baselineEligible=baselineEligible; this.candidateEligible=candidateEligible;
        this.baselineCoverage=baselineCoverage; this.candidateCoverage=candidateCoverage;
        this.baselineValue=baselineValue; this.candidateValue=candidateValue;
        this.absoluteDifference=absoluteDifference; this.relativeDifferencePercent=relativeDifferencePercent;
        this.direction=direction; this.evidenceFingerprint=fingerprint; this.rationale=rationale;
        this.limitation=limitation; this.createdByUser=createdByUser; this.createdAt=now;this.origin=origin;
        this.automationEngineVersion=automationEngineVersion;this.automationRobotId=automationRobotId;
        this.automationPolicyRevision=automationPolicyRevision;this.automationTrigger=automationTrigger;
        this.automationOpportunityFingerprint=automationOpportunityFingerprint;
    }
    public void supersede(){this.current=false;}
    public void approve(Instant now){require(Status.READY_FOR_REVIEW);status=Status.APPROVED;reviewedAt=now;}
    public void reject(Instant now){require(Status.READY_FOR_REVIEW);status=Status.REJECTED;reviewedAt=now;}
    public void markStale(Instant now){status=Status.STALE;reviewedAt=now;}
    public void materialize(UUID experimentId,Instant now){require(Status.APPROVED);materializedExperimentId=experimentId;status=Status.MATERIALIZED;materializedAt=now;}
    private void require(Status expected){if(status!=expected)throw new IllegalStateException("Proposal must be "+expected);}
    public UUID getId(){return id;} public Workspace getWorkspace(){return workspace;}
    public CampaignPerformanceReview getSourceReview(){return sourceReview;} public int getRevision(){return revision;}
    public boolean isCurrent(){return current;} public String getEngineVersion(){return engineVersion;}
    public String getFactor(){return factor;} public Status getStatus(){return status;}
    public UUID getBaselinePersonaId(){return baselinePersonaId;} public String getBaselinePersonaNameSnapshot(){return baselinePersonaNameSnapshot;}
    public String getBaselinePersonaFingerprint(){return baselinePersonaFingerprint;}
    public UUID getCandidatePersonaId(){return candidatePersonaId;} public String getCandidatePersonaNameSnapshot(){return candidatePersonaNameSnapshot;}
    public String getCandidatePersonaFingerprint(){return candidatePersonaFingerprint;}
    public DashboardQuery.Metric getMetric(){return metric;} public Statistic getStatistic(){return statistic;}
    public DashboardQuery.Window getObservationWindow(){return observationWindow;} public String getProvider(){return provider;}
    public Instant getCohortFrom(){return cohortFrom;} public Instant getCohortTo(){return cohortTo;}
    public int getBaselineSample(){return baselineSample;} public int getCandidateSample(){return candidateSample;}
    public int getBaselineEligible(){return baselineEligible;} public int getCandidateEligible(){return candidateEligible;}
    public BigDecimal getBaselineCoverage(){return baselineCoverage;} public BigDecimal getCandidateCoverage(){return candidateCoverage;}
    public BigDecimal getBaselineValue(){return baselineValue;} public BigDecimal getCandidateValue(){return candidateValue;}
    public BigDecimal getAbsoluteDifference(){return absoluteDifference;} public BigDecimal getRelativeDifferencePercent(){return relativeDifferencePercent;}
    public Direction getDirection(){return direction;} public String getEvidenceFingerprint(){return evidenceFingerprint;}
    public String getRationale(){return rationale;} public String getLimitation(){return limitation;}
    public UUID getMaterializedExperimentId(){return materializedExperimentId;} public Instant getCreatedAt(){return createdAt;}
    public Instant getReviewedAt(){return reviewedAt;} public Instant getMaterializedAt(){return materializedAt;}
    public Origin getOrigin(){return origin;} public String getAutomationEngineVersion(){return automationEngineVersion;}
    public UUID getAutomationRobotId(){return automationRobotId;} public Integer getAutomationPolicyRevision(){return automationPolicyRevision;}
    public String getAutomationTrigger(){return automationTrigger;} public String getAutomationOpportunityFingerprint(){return automationOpportunityFingerprint;}
}
