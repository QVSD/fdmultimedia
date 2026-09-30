package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.CampaignPerformanceModels.RecommendationType;
import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name="campaign_performance_recommendations")
public class CampaignPerformanceRecommendation {
    @Id private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="review_id") private CampaignPerformanceReview review;
    @Column(nullable=false) private int sequence;
    @Enumerated(EnumType.STRING) @Column(name="recommendation_type",nullable=false) private RecommendationType type;
    @Enumerated(EnumType.STRING) private Metric metric;
    @Column(name="compared_dimension") private String comparedDimension;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb",nullable=false) private Map<String,Object> evidence;
    @Column(nullable=false) private String message;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb",nullable=false) private List<String> limitations;
    @Column(name="created_at",nullable=false) private Instant createdAt;

    protected CampaignPerformanceRecommendation() {}
    public CampaignPerformanceRecommendation(CampaignPerformanceReview review,int sequence,RecommendationType type,Metric metric,
            String dimension,Map<String,Object> evidence,String message,List<String> limitations,Instant now){
        id=UUID.randomUUID();this.review=review;this.sequence=sequence;this.type=type;this.metric=metric;
        comparedDimension=dimension;this.evidence=new LinkedHashMap<>(evidence);this.message=message;
        this.limitations=List.copyOf(limitations);createdAt=now;
    }
    public UUID getId(){return id;} public int getSequence(){return sequence;} public RecommendationType getType(){return type;}
    public Metric getMetric(){return metric;} public String getComparedDimension(){return comparedDimension;}
    public Map<String,Object> getEvidence(){return Collections.unmodifiableMap(evidence);} public String getMessage(){return message;}
    public List<String> getLimitations(){return Collections.unmodifiableList(limitations);}
}
