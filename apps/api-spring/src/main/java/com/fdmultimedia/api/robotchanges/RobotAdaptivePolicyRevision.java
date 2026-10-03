package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="robot_adaptive_policy_revisions")
public class RobotAdaptivePolicyRevision {
    @Id private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="workspace_id") private Workspace workspace;
    @Column(name="robot_id",nullable=false) private UUID robotId;
    @Column(nullable=false) private int revision;
    @Column(name="previous_values") private String previousValues;
    @Column(name="new_values",nullable=false) private String newValues;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="actor_user_id") private AppUser actor;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected RobotAdaptivePolicyRevision(){}
    public RobotAdaptivePolicyRevision(Workspace w,UUID robotId,int revision,String oldValues,String newValues,AppUser actor,Instant now){
        id=UUID.randomUUID();workspace=w;this.robotId=robotId;this.revision=revision;previousValues=oldValues;
        this.newValues=newValues;this.actor=actor;createdAt=now;}
    public UUID getId(){return id;} public UUID getRobotId(){return robotId;} public int getRevision(){return revision;}
    public String getPreviousValues(){return previousValues;} public String getNewValues(){return newValues;}
    public AppUser getActor(){return actor;} public Instant getCreatedAt(){return createdAt;}
}
