package com.fdmultimedia.api.campaigns;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignContentPlanItemRepository extends JpaRepository<CampaignContentPlanItem, UUID> {

    List<CampaignContentPlanItem> findByPlanOrderBySequenceAsc(CampaignContentPlan plan);

    Optional<CampaignContentPlanItem> findByPlanAndRobotRunOutputId(CampaignContentPlan plan, UUID robotRunOutputId);
}
