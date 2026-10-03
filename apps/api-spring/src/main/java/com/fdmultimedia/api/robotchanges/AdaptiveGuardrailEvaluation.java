package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.*;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity @Table(name="adaptive_guardrail_evaluations")
public class AdaptiveGuardrailEvaluation {
    @Id private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="workspace_id") private Workspace workspace;
    @Column(name="proposal_id",nullable=false) private UUID proposalId;
    @Column(name="robot_id",nullable=false) private UUID robotId;
    @Column(name="engine_version",nullable=false) private String engineVersion;
    @Enumerated(EnumType.STRING) @Column(name="trigger_type",nullable=false) private Trigger trigger;
    @Column(name="policy_revision",nullable=false) private int policyRevision;
    @Column(nullable=false) private boolean eligible;
    @Column(name="reason_codes",nullable=false) private String reasonCodes;
    @Column(name="budget_allowed",nullable=false) private int budgetAllowed;
    @Column(name="budget_used",nullable=false) private int budgetUsed;
    @Column(name="budget_window_days",nullable=false) private int budgetWindowDays;
    @Column(name="latest_configuration_revision_id") private UUID latestConfigurationRevisionId;
    @Column(name="last_configuration_change_at") private Instant lastConfigurationChangeAt;
    @Column(name="cooldown_hours",nullable=false) private int cooldownHours;
    @Column(name="cooldown_ends_at") private Instant cooldownEndsAt;
    @Column(name="active_experiment_id") private UUID activeExperimentId;
    @Column(name="pending_proposal_count",nullable=false) private int pendingProposalCount;
    @Column(name="runs_since_revision",nullable=false) private int runsSinceRevision;
    @Column(name="publications_since_revision",nullable=false) private int publicationsSinceRevision;
    @Column(name="eligible_by_age_count",nullable=false) private int eligibleByAgeCount;
    @Column(name="analytics_publication_count",nullable=false) private int analyticsPublicationCount;
    @Column(name="metric_sample_count",nullable=false) private int metricSampleCount;
    @Column(precision=7,scale=6) private BigDecimal coverage;
    @Column(name="evaluated_at",nullable=false) private Instant evaluatedAt;
    protected AdaptiveGuardrailEvaluation(){}
    public AdaptiveGuardrailEvaluation(Workspace w,UUID proposal,UUID robot,Trigger trigger,int policyRevision,List<Reason> reasons,
            int allowed,int used,int days,UUID latest,Instant last,int cooldown,Instant cooldownEnd,UUID activeExperiment,
            int pending,Observation o,Instant now){id=UUID.randomUUID();workspace=w;proposalId=proposal;robotId=robot;
        engineVersion=AdaptiveGuardrailService.ENGINE_VERSION;this.trigger=trigger;this.policyRevision=policyRevision;
        eligible=reasons.isEmpty();reasonCodes=reasons.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(","));
        budgetAllowed=allowed;budgetUsed=used;budgetWindowDays=days;latestConfigurationRevisionId=latest;
        lastConfigurationChangeAt=last;cooldownHours=cooldown;cooldownEndsAt=cooldownEnd;activeExperimentId=activeExperiment;
        pendingProposalCount=pending;runsSinceRevision=o.runs();publicationsSinceRevision=o.publications();
        eligibleByAgeCount=o.eligibleByAge();analyticsPublicationCount=o.analyticsPublications();metricSampleCount=o.metricSamples();
        coverage=o.coverage();evaluatedAt=now;}
    public UUID getId(){return id;} public UUID getProposalId(){return proposalId;} public UUID getRobotId(){return robotId;}
    public String getEngineVersion(){return engineVersion;} public Trigger getTrigger(){return trigger;}
    public int getPolicyRevision(){return policyRevision;} public boolean isEligible(){return eligible;}
    public String getReasonCodes(){return reasonCodes;} public int getBudgetAllowed(){return budgetAllowed;}
    public int getBudgetUsed(){return budgetUsed;} public int getBudgetWindowDays(){return budgetWindowDays;}
    public UUID getLatestConfigurationRevisionId(){return latestConfigurationRevisionId;}
    public Instant getLastConfigurationChangeAt(){return lastConfigurationChangeAt;} public int getCooldownHours(){return cooldownHours;}
    public Instant getCooldownEndsAt(){return cooldownEndsAt;} public UUID getActiveExperimentId(){return activeExperimentId;}
    public int getPendingProposalCount(){return pendingProposalCount;} public int getRunsSinceRevision(){return runsSinceRevision;}
    public int getPublicationsSinceRevision(){return publicationsSinceRevision;} public int getEligibleByAgeCount(){return eligibleByAgeCount;}
    public int getAnalyticsPublicationCount(){return analyticsPublicationCount;} public int getMetricSampleCount(){return metricSampleCount;}
    public BigDecimal getCoverage(){return coverage;} public Instant getEvaluatedAt(){return evaluatedAt;}
}
