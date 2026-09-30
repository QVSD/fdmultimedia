package com.fdmultimedia.api.analytics;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignPerformanceReviewOutputRepository extends JpaRepository<CampaignPerformanceReviewOutput,UUID> {
    List<CampaignPerformanceReviewOutput> findByReviewOrderBySelectionOrderAscIdAsc(CampaignPerformanceReview review);
}
