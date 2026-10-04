package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.robotchanges.RobotAdaptivePolicyModels.ProposalAutomationMode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="robot_adaptive_policies")
public class RobotAdaptivePolicy {
    @Id private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="workspace_id") private Workspace workspace;
    @Column(name="robot_id",nullable=false) private UUID robotId;
    @Column(nullable=false) private int revision;
    @Column(nullable=false) private boolean enabled;
    @Column(name="max_applied_changes_per_window",nullable=false) private int maxAppliedChangesPerWindow;
    @Column(name="change_budget_window_days",nullable=false) private int changeBudgetWindowDays;
    @Column(name="cooldown_hours",nullable=false) private int cooldownHours;
    @Column(name="require_no_active_experiment",nullable=false) private boolean requireNoActiveExperiment;
    @Column(name="require_no_pending_change",nullable=false) private boolean requireNoPendingChange;
    @Column(name="require_post_change_observation",nullable=false) private boolean requirePostChangeObservation;
    @Enumerated(EnumType.STRING) @Column(name="proposal_automation_mode",nullable=false)
    private ProposalAutomationMode proposalAutomationMode;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="updated_by_user_id") private AppUser updatedBy;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;

    protected RobotAdaptivePolicy() {}
    public RobotAdaptivePolicy(Workspace workspace, UUID robotId, AppUser actor, Instant now) {
        id=UUID.randomUUID();this.workspace=workspace;this.robotId=robotId;revision=1;enabled=true;
        maxAppliedChangesPerWindow=2;changeBudgetWindowDays=30;cooldownHours=72;
        requireNoActiveExperiment=true;requireNoPendingChange=true;requirePostChangeObservation=true;
        proposalAutomationMode=ProposalAutomationMode.MANUAL_ONLY;
        updatedBy=actor;updatedAt=now;
    }
    public RobotAdaptivePolicy(Workspace workspace,UUID robotId,boolean enabled,int max,int days,int cooldown,
            boolean noExperiment,boolean noPending,boolean observation,AppUser actor,Instant now){
        this(workspace,robotId,enabled,max,days,cooldown,noExperiment,noPending,observation,
                ProposalAutomationMode.MANUAL_ONLY,actor,now);
    }
    public RobotAdaptivePolicy(Workspace workspace,UUID robotId,boolean enabled,int max,int days,int cooldown,
            boolean noExperiment,boolean noPending,boolean observation,ProposalAutomationMode automationMode,AppUser actor,Instant now){
        id=UUID.randomUUID();this.workspace=workspace;this.robotId=robotId;revision=1;this.enabled=enabled;
        maxAppliedChangesPerWindow=max;changeBudgetWindowDays=days;cooldownHours=cooldown;
        requireNoActiveExperiment=noExperiment;requireNoPendingChange=noPending;requirePostChangeObservation=observation;
        proposalAutomationMode=automationMode;
        updatedBy=actor;updatedAt=now;
    }
    public void update(boolean enabled,int max,int days,int cooldown,boolean noExperiment,boolean noPending,
            boolean observation,AppUser actor,Instant now){update(enabled,max,days,cooldown,noExperiment,noPending,
                observation,proposalAutomationMode,actor,now);}
    public void update(boolean enabled,int max,int days,int cooldown,boolean noExperiment,boolean noPending,
            boolean observation,ProposalAutomationMode automationMode,AppUser actor,Instant now){revision++;this.enabled=enabled;maxAppliedChangesPerWindow=max;
        changeBudgetWindowDays=days;cooldownHours=cooldown;requireNoActiveExperiment=noExperiment;
        requireNoPendingChange=noPending;requirePostChangeObservation=observation;proposalAutomationMode=automationMode;updatedBy=actor;updatedAt=now;}
    public UUID getId(){return id;} public Workspace getWorkspace(){return workspace;} public UUID getRobotId(){return robotId;}
    public int getRevision(){return revision;} public boolean isEnabled(){return enabled;}
    public int getMaxAppliedChangesPerWindow(){return maxAppliedChangesPerWindow;}
    public int getChangeBudgetWindowDays(){return changeBudgetWindowDays;} public int getCooldownHours(){return cooldownHours;}
    public boolean isRequireNoActiveExperiment(){return requireNoActiveExperiment;}
    public boolean isRequireNoPendingChange(){return requireNoPendingChange;}
    public boolean isRequirePostChangeObservation(){return requirePostChangeObservation;}
    public ProposalAutomationMode getProposalAutomationMode(){return proposalAutomationMode;}
    public AppUser getUpdatedBy(){return updatedBy;} public Instant getUpdatedAt(){return updatedAt;}
}
