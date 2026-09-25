package com.fdmultimedia.api.campaigns;

import static org.assertj.core.api.Assertions.assertThat;

import com.fdmultimedia.api.assets.MediaAsset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DeterministicCampaignPlannerTest {

    private final DeterministicCampaignPlanner planner = new DeterministicCampaignPlanner();

    @Test
    void oneOutputIsStandalone() {
        assertThat(planner.roles(1)).containsExactly(CampaignPlanRole.STANDALONE);
    }

    @Test
    void twoOutputsAreIntroductionAndConclusion() {
        assertThat(planner.roles(2)).containsExactly(CampaignPlanRole.INTRODUCTION, CampaignPlanRole.CONCLUSION);
    }

    @Test
    void threeOutputsAreIntroductionDeepDiveConclusion() {
        assertThat(planner.roles(3)).containsExactly(
                CampaignPlanRole.INTRODUCTION, CampaignPlanRole.DEEP_DIVE, CampaignPlanRole.CONCLUSION);
    }

    @Test
    void fourOutputsAddASupportingPoint() {
        assertThat(planner.roles(4)).containsExactly(
                CampaignPlanRole.INTRODUCTION, CampaignPlanRole.DEEP_DIVE,
                CampaignPlanRole.SUPPORTING_POINT, CampaignPlanRole.CONCLUSION);
    }

    @Test
    void fiveOutputsHaveTwoDeepDives() {
        assertThat(planner.roles(5)).containsExactly(
                CampaignPlanRole.INTRODUCTION, CampaignPlanRole.DEEP_DIVE, CampaignPlanRole.DEEP_DIVE,
                CampaignPlanRole.SUPPORTING_POINT, CampaignPlanRole.CONCLUSION);
    }

    @Test
    void zeroAndSixAreRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner.roles(0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner.roles(6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planSequenceMatchesPositionOneIndexed() {
        DeterministicCampaignPlanner.Plan plan = planner.plan(3, null);

        List<Integer> sequences = plan.items().stream()
                .map(DeterministicCampaignPlanner.PlannedItem::sequence)
                .collect(Collectors.toList());
        assertThat(sequences).containsExactly(1, 2, 3);
    }

    @Test
    void planNeverInventsFactsAboutSourceWhenAssetIsNull() {
        DeterministicCampaignPlanner.Plan plan = planner.plan(1, null);

        assertThat(plan.campaignTitle()).isEqualTo("Content series");
    }

    @Test
    void planUsesSourceFilenameWhenAvailable() {
        MediaAsset asset = mockAssetWithFilename("keynote.mp4");

        DeterministicCampaignPlanner.Plan plan = planner.plan(2, asset);

        assertThat(plan.campaignTitle()).isEqualTo("Series from keynote.mp4");
    }

    @Test
    void singleOutputHasNoAvoidRepetitionGuidance() {
        DeterministicCampaignPlanner.Plan plan = planner.plan(1, null);

        assertThat(plan.items().get(0).avoidRepetitionGuidance()).isNull();
    }

    @Test
    void multiOutputPlanIncludesAvoidRepetitionGuidanceForEveryItem() {
        DeterministicCampaignPlanner.Plan plan = planner.plan(4, null);

        assertThat(plan.items()).allSatisfy(item -> assertThat(item.avoidRepetitionGuidance()).isNotBlank());
    }

    @Test
    void guidanceNeverClaimsHumanLevelCreativeStrategy() {
        DeterministicCampaignPlanner.Plan plan = planner.plan(5, null);

        String allText = plan.campaignTitle() + " " + plan.campaignAngle() + " "
                + plan.items().stream()
                        .map(i -> i.hookGuidance() + " " + i.captionGuidance())
                        .collect(Collectors.joining(" "));
        assertThat(allText.toLowerCase()).doesNotContain("optimized", "viral", "guaranteed", "best strategy");
    }

    private MediaAsset mockAssetWithFilename(String filename) {
        MediaAsset asset = org.mockito.Mockito.mock(MediaAsset.class);
        org.mockito.Mockito.when(asset.getOriginalFilename()).thenReturn(filename);
        return asset;
    }
}
