package com.fdmultimedia.api.analytics;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignPerformanceRecommendationRepository extends JpaRepository<CampaignPerformanceRecommendation,UUID> {
    List<CampaignPerformanceRecommendation> findByReviewOrderBySequenceAsc(CampaignPerformanceReview review);
}
