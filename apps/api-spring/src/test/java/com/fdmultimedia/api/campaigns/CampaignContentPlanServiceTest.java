package com.fdmultimedia.api.campaigns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentsuggestions.ContentAiProperties;
import com.fdmultimedia.api.highlights.HighlightCandidate;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobService;
import com.fdmultimedia.api.jobs.JobStatus;
import com.fdmultimedia.api.jobs.JobSummary;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.personas.PersonaRepository;
import com.fdmultimedia.api.robots.CampaignPlanningPolicy;
import com.fdmultimedia.api.robots.Robot;
import com.fdmultimedia.api.robots.RobotAiPolicy;
import com.fdmultimedia.api.robots.RobotHighlightStrategy;
import com.fdmultimedia.api.robots.RobotRun;
import com.fdmultimedia.api.robots.RobotRunOutput;
import com.fdmultimedia.api.robots.RobotRunOutputRepository;
import com.fdmultimedia.api.robots.RobotRunRepository;
import com.fdmultimedia.api.robots.RobotRunTriggerType;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.WorkerCredential;
import com.fdmultimedia.api.workers.WorkerRegistrationRequest;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CampaignContentPlanServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final RobotRunRepository runs = mock(RobotRunRepository.class);
    private final RobotRunOutputRepository outputsRepository = mock(RobotRunOutputRepository.class);
    private final CampaignContentPlanRepository plans = mock(CampaignContentPlanRepository.class);
    private final CampaignContentPlanItemRepository items = mock(CampaignContentPlanItemRepository.class);
    private final DeterministicCampaignPlanner deterministicPlanner = new DeterministicCampaignPlanner();
    private final CampaignPlanPromptBuilder promptBuilder = new CampaignPlanPromptBuilder();
    private final CampaignRepetitionDetector repetitionDetector = new CampaignRepetitionDetector();
    private final CampaignPlanProperties properties = new CampaignPlanProperties();
    private final ContentAiProperties aiProperties = new ContentAiProperties();
    private final PersonaRepository personas = mock(PersonaRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final CampaignContentPlanService service = new CampaignContentPlanService(
            authService, runs, outputsRepository, plans, items, deterministicPlanner, promptBuilder,
            repetitionDetector, properties, aiProperties, personas, jobService, Clock.fixed(NOW, ZoneOffset.UTC));

    private final List<CampaignContentPlan> savedPlans = new ArrayList<>();
    private final List<CampaignContentPlanItem> savedItems = new ArrayList<>();

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private Robot robot;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));

        robot = mock(Robot.class);
        when(robot.getAiPolicy()).thenReturn(RobotAiPolicy.NO_AI);
        when(robot.getPersona()).thenReturn(null);
        when(robot.getAiLanguageOverride()).thenReturn(null);
        when(robot.getAiToneOverride()).thenReturn(null);
        when(robot.getHighlightStrategy()).thenReturn(RobotHighlightStrategy.TOP_DIVERSE_HIGHLIGHTS);
        when(robot.getHighlightCount()).thenReturn(3);
        when(robot.getOutputSpacingMinutes()).thenReturn(60);
        when(robot.getCampaignPlanningPolicy()).thenReturn(CampaignPlanningPolicy.DETERMINISTIC_PLAN);
        when(robot.getCreatedByUser()).thenReturn(owner);

        when(plans.save(any(CampaignContentPlan.class))).thenAnswer(inv -> {
            CampaignContentPlan plan = inv.getArgument(0);
            savedPlans.add(plan);
            return plan;
        });
        when(plans.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return savedPlans.stream().filter(p -> p.getId().equals(id)).findFirst();
        });
        when(plans.findByRobotRunOrderByRevisionDesc(any())).thenAnswer(inv -> {
            RobotRun run = inv.getArgument(0);
            return savedPlans.stream()
                    .filter(p -> p.getRobotRun().getId().equals(run.getId()))
                    .sorted(Comparator.comparingInt(CampaignContentPlan::getRevision).reversed())
                    .toList();
        });
        when(plans.findByGenerationJobId(any(UUID.class))).thenAnswer(inv -> {
            UUID jobId = inv.getArgument(0);
            return savedPlans.stream()
                    .filter(p -> p.getGenerationJob() != null && p.getGenerationJob().getId().equals(jobId))
                    .findFirst();
        });
        when(plans.findByWorkspaceAndId(any(), any())).thenAnswer(inv -> {
            Workspace ws = inv.getArgument(0);
            UUID id = inv.getArgument(1);
            return savedPlans.stream().filter(p -> p.getWorkspace().equals(ws) && p.getId().equals(id)).findFirst();
        });

        when(items.saveAll(any())).thenAnswer(inv -> {
            List<CampaignContentPlanItem> list = inv.getArgument(0);
            savedItems.addAll(list);
            return list;
        });
        when(items.findByPlanOrderBySequenceAsc(any())).thenAnswer(inv -> {
            CampaignContentPlan plan = inv.getArgument(0);
            return savedItems.stream()
                    .filter(i -> i.getPlan().getId().equals(plan.getId()))
                    .sorted(Comparator.comparingInt(CampaignContentPlanItem::getSequence))
                    .toList();
        });
        when(items.findByPlanAndRobotRunOutputId(any(), any())).thenAnswer(inv -> {
            CampaignContentPlan plan = inv.getArgument(0);
            UUID outputId = inv.getArgument(1);
            return savedItems.stream()
                    .filter(i -> i.getPlan().getId().equals(plan.getId()) && i.getRobotRunOutputId().equals(outputId))
                    .findFirst();
        });

        doAnswer(inv -> {
            Job job = inv.getArgument(0);
            Worker assignedWorker = inv.getArgument(1);
            Map<String, Object> result = inv.getArgument(2);
            Instant now = inv.getArgument(3);
            job.complete(assignedWorker, result, now);
            return null;
        }).when(jobService).completeOwnedJob(any(Job.class), any(Worker.class), any(Map.class), any(Instant.class));
        doAnswer(inv -> {
            Job job = inv.getArgument(0);
            Worker assignedWorker = inv.getArgument(1);
            String errorCode = inv.getArgument(2);
            String errorMessage = inv.getArgument(3);
            boolean terminal = inv.getArgument(4);
            Instant now = inv.getArgument(5);
            if (terminal) {
                job.failTerminal(assignedWorker, errorCode, errorMessage, now);
            } else {
                job.fail(assignedWorker, errorCode, errorMessage, now);
            }
            return null;
        }).when(jobService).failOwnedJob(any(Job.class), any(Worker.class), any(), any(), anyBoolean(), any(Instant.class));
    }

    // ---- advance() / deterministic baseline ----

    @Test
    void noCampaignPlanPolicyIsANoOp() {
        when(robot.getCampaignPlanningPolicy()).thenReturn(CampaignPlanningPolicy.NO_CAMPAIGN_PLAN);
        RobotRun run = newRun();
        List<RobotRunOutput> outputs = List.of(output(1));

        service.advance(run, outputs, null, NOW);

        assertThat(run.getCampaignPlanId()).isNull();
        assertThat(savedPlans).isEmpty();
    }

    @Test
    void deterministicPlanAutoAppliesAndBindsToTheRun() {
        RobotRun run = newRun();
        List<RobotRunOutput> outputs = List.of(output(1), output(2), output(3));

        service.advance(run, outputs, null, NOW);

        assertThat(savedPlans).hasSize(1);
        CampaignContentPlan plan = savedPlans.get(0);
        assertThat(plan.getStatus()).isEqualTo(CampaignPlanStatus.APPLIED);
        assertThat(plan.getRevision()).isEqualTo(1);
        assertThat(run.getCampaignPlanId()).isEqualTo(plan.getId());
    }

    @Test
    void deterministicPlanPersistsExactlyOneItemPerOutputInSelectionOrder() {
        RobotRun run = newRun();
        RobotRunOutput first = output(1);
        RobotRunOutput second = output(2);
        RobotRunOutput third = output(3);
        List<RobotRunOutput> outputs = List.of(first, second, third);

        service.advance(run, outputs, null, NOW);

        assertThat(savedItems).hasSize(3);
        assertThat(savedItems.stream().map(CampaignContentPlanItem::getSequence)).containsExactly(1, 2, 3);
        assertThat(savedItems.stream().map(CampaignContentPlanItem::getRobotRunOutputId))
                .containsExactly(first.getId(), second.getId(), third.getId());
    }

    @Test
    void advanceIsIdempotentAcrossMultipleReconcilerPasses() {
        RobotRun run = newRun();
        List<RobotRunOutput> outputs = List.of(output(1));

        service.advance(run, outputs, null, NOW);
        service.advance(run, outputs, null, NOW);
        service.advance(run, outputs, null, NOW);

        assertThat(savedPlans).hasSize(1);
    }

    @Test
    void isBlockingAiGenerationFalseForNoCampaignPlanPolicy() {
        when(robot.getCampaignPlanningPolicy()).thenReturn(CampaignPlanningPolicy.NO_CAMPAIGN_PLAN);
        RobotRun run = newRun();

        assertThat(service.isBlockingAiGeneration(run)).isFalse();
    }

    @Test
    void isBlockingAiGenerationTrueBeforeAnyPlanExists() {
        RobotRun run = newRun();

        assertThat(service.isBlockingAiGeneration(run)).isTrue();
    }

    @Test
    void isBlockingAiGenerationFalseOnceDeterministicPlanAutoApplied() {
        RobotRun run = newRun();
        service.advance(run, List.of(output(1)), null, NOW);

        assertThat(service.isBlockingAiGeneration(run)).isFalse();
    }

    @Test
    void findItemForOnlyReturnsGuidanceOnceThePlanIsApplied() {
        RobotRun run = newRun();
        RobotRunOutput out = output(1);
        service.advance(run, List.of(out), null, NOW);

        Optional<CampaignContentPlanItem> item = service.findItemFor(run, out);

        assertThat(item).isPresent();
        assertThat(item.get().getRobotRunOutputId()).isEqualTo(out.getId());
    }

    @Test
    void findItemForIsEmptyBeforeAnyPlanIsBound() {
        RobotRun run = newRun();
        RobotRunOutput out = output(1);

        assertThat(service.findItemFor(run, out)).isEmpty();
    }

    @Test
    void blockedFailureCodeEmptyForNoCampaignPlanPolicy() {
        when(robot.getCampaignPlanningPolicy()).thenReturn(CampaignPlanningPolicy.NO_CAMPAIGN_PLAN);
        RobotRun run = newRun();

        assertThat(service.blockedFailureCode(run)).isEmpty();
    }

    @Test
    void blockedFailureCodeEmptyOnceDeterministicPlanIsApplied() {
        RobotRun run = newRun();
        service.advance(run, List.of(output(1)), null, NOW);

        assertThat(service.blockedFailureCode(run)).isEmpty();
    }

    @Test
    void guidanceRoleAndSequenceMappingForEveryOutputCount() {
        for (int count = 1; count <= 5; count++) {
            RobotRun run = newRun();
            List<RobotRunOutput> outputs = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                outputs.add(output(i));
            }
            savedPlans.clear();
            savedItems.clear();

            service.advance(run, outputs, null, NOW);

            assertThat(savedItems).hasSize(count);
        }
    }

    // ---- lifecycle: apply / reject / regenerate ----

    @Test
    void applyIsIdempotentOnAnAlreadyAppliedPlan() {
        RobotRun run = newRun();
        service.advance(run, List.of(output(1)), null, NOW);
        CampaignContentPlan plan = savedPlans.get(0);

        CampaignContentPlanSummary first = service.apply(user, plan.getId());
        CampaignContentPlanSummary second = service.apply(user, plan.getId());

        assertThat(first.status()).isEqualTo(CampaignPlanStatus.APPLIED);
        assertThat(second.status()).isEqualTo(CampaignPlanStatus.APPLIED);
        assertThat(second.appliedAt()).isEqualTo(first.appliedAt());
    }

    @Test
    void applyFromReadyForReviewSucceedsWhenInputsAreUnchanged() {
        WorkerFixture fixture = aiPlanInFlight(2);
        List<WorkerCampaignPlanItemRequest> validItems = List.of(
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(0).getId(), "INTRODUCTION", "hook a", "caption a", null, null),
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(1).getId(), "CONCLUSION", "a distinct closing hook", "caption b", null, null));
        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCampaignPlanCompletionRequest("machine-1", fixture.plan.getId(), "Title", "Angle", validItems));
        assertThat(fixture.plan.getStatus()).isEqualTo(CampaignPlanStatus.READY_FOR_REVIEW);

        CampaignContentPlanSummary applied = service.apply(user, fixture.plan.getId());

        assertThat(applied.status()).isEqualTo(CampaignPlanStatus.APPLIED);
    }

    @Test
    void applyFailsSafelyWhenInputsHaveBecomeStale() {
        WorkerFixture fixture = aiPlanInFlight(2);
        List<WorkerCampaignPlanItemRequest> validItems = List.of(
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(0).getId(), "INTRODUCTION", "hook a", "caption a", null, null),
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(1).getId(), "CONCLUSION", "a distinct closing hook", "caption b", null, null));
        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCampaignPlanCompletionRequest("machine-1", fixture.plan.getId(), "Title", "Angle", validItems));
        // Simulate the only realistic drift the fingerprint can ever catch under this
        // architecture: the underlying output set no longer matches what the plan was
        // generated from (defensive safety net — never expected in real operation).
        when(outputsRepository.findByRobotRunOrderBySelectionOrderAsc(fixture.run)).thenReturn(List.of(fixture.outputs.get(0)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.apply(user, fixture.plan.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CAMPAIGN_PLAN_STALE");
        assertThat(fixture.plan.getStatus()).isEqualTo(CampaignPlanStatus.READY_FOR_REVIEW);
    }

    @Test
    void applyRejectsAPlanThatIsNotReadyForReview() {
        when(robot.getCampaignPlanningPolicy()).thenReturn(CampaignPlanningPolicy.AI_PLAN_FOR_REVIEW);
        RobotRun run = newRun();
        Job generationJob = new Job(workspace, JobType.GENERATE_CAMPAIGN_PLAN, Map.of(), 3, NOW);
        when(jobService.createForWorkspace(any(), any())).thenReturn(jobSummary(generationJob));
        when(jobService.getJobEntityForWorkspace(workspace, generationJob.getId())).thenReturn(Optional.of(generationJob));
        service.advance(run, List.of(output(1)), null, NOW);
        CampaignContentPlan plan = savedPlans.get(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.apply(user, plan.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void regenerateSupersedesTheCurrentPlanAndCreatesANewRevision() {
        RobotRun run = newRun();
        when(runs.findByWorkspaceAndId(workspace, run.getId())).thenReturn(Optional.of(run));
        RobotRunOutput out = output(1);
        when(outputsRepository.findByRobotRunOrderBySelectionOrderAsc(run)).thenReturn(List.of(out));
        service.advance(run, List.of(out), null, NOW);
        CampaignContentPlan firstRevision = savedPlans.get(0);

        CampaignContentPlanSummary regenerated = service.regenerate(user, run.getId());

        assertThat(savedPlans).hasSize(2);
        assertThat(firstRevision.isCurrent()).isFalse();
        assertThat(regenerated.revision()).isEqualTo(2);
        assertThat(regenerated.current()).isTrue();
    }

    @Test
    void regenerateRejectedForNoCampaignPlanPolicy() {
        when(robot.getCampaignPlanningPolicy()).thenReturn(CampaignPlanningPolicy.NO_CAMPAIGN_PLAN);
        RobotRun run = newRun();
        when(runs.findByWorkspaceAndId(workspace, run.getId())).thenReturn(Optional.of(run));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.regenerate(user, run.getId()))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ---- AI path: job dispatch, structured validation, worker completion ----

    @Test
    void aiPolicyDispatchesAGenerationJobAndBlocksUntilCompletion() {
        when(robot.getCampaignPlanningPolicy()).thenReturn(CampaignPlanningPolicy.AI_PLAN_FOR_REVIEW);
        RobotRun run = newRun();
        Job generationJob = new Job(workspace, JobType.GENERATE_CAMPAIGN_PLAN, Map.of(), 3, NOW);
        when(jobService.createForWorkspace(any(), any())).thenReturn(jobSummary(generationJob));
        when(jobService.getJobEntityForWorkspace(workspace, generationJob.getId())).thenReturn(Optional.of(generationJob));

        service.advance(run, List.of(output(1), output(2)), null, NOW);

        assertThat(run.getCampaignPlanId()).isNotNull();
        assertThat(service.isBlockingAiGeneration(run)).isTrue();
        assertThat(savedItems).isEmpty();
    }

    @Test
    void aiDisabledFailsThePlanWithoutDispatchingAJob() {
        aiProperties.setEnabled(false);
        when(robot.getCampaignPlanningPolicy()).thenReturn(CampaignPlanningPolicy.AI_PLAN_FOR_REVIEW);
        RobotRun run = newRun();

        service.advance(run, List.of(output(1)), null, NOW);

        assertThat(savedPlans.get(0).getStatus()).isEqualTo(CampaignPlanStatus.FAILED);
        assertThat(savedPlans.get(0).getFailureCode()).isEqualTo("AI_DISABLED");
        assertThat(service.blockedFailureCode(run)).contains("CAMPAIGN_PLAN_FAILED");
        verify(jobService, never()).createForWorkspace(any(), any());
    }

    @Test
    void completeWorkerGenerationPersistsAValidStructuredResponse() {
        WorkerFixture fixture = aiPlanInFlight(2);

        List<WorkerCampaignPlanItemRequest> validItems = List.of(
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(0).getId(), "INTRODUCTION",
                        "Open with the setup", "Set the scene for part one", "Follow for part two", null),
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(1).getId(), "CONCLUSION",
                        "Wrap up with the payoff", "Bring the series home with a clear takeaway", null, "avoid repeating part one"));
        WorkerCampaignPlanCompletionRequest request = new WorkerCampaignPlanCompletionRequest(
                "machine-1", fixture.plan.getId(), "Great Series", "A two-part story", validItems);

        CampaignContentPlanSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(summary.status()).isEqualTo(CampaignPlanStatus.READY_FOR_REVIEW);
        assertThat(summary.items()).hasSize(2);
    }

    @Test
    void completeWorkerGenerationAutoAppliesForAiPlanAndApply() {
        WorkerFixture fixture = aiPlanInFlight(1, CampaignPlanningPolicy.AI_PLAN_AND_APPLY);

        List<WorkerCampaignPlanItemRequest> validItems = List.of(
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(0).getId(), "STANDALONE",
                        "Open strong", "Present the complete highlight", null, null));
        WorkerCampaignPlanCompletionRequest request = new WorkerCampaignPlanCompletionRequest(
                "machine-1", fixture.plan.getId(), "Series", "Angle", validItems);

        CampaignContentPlanSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(summary.status()).isEqualTo(CampaignPlanStatus.APPLIED);
    }

    @Test
    void rejectsWhenAnOutputIsMissingFromTheResponse() {
        WorkerFixture fixture = aiPlanInFlight(2);
        List<WorkerCampaignPlanItemRequest> onlyOne = List.of(
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(0).getId(), "INTRODUCTION", "hook", "caption", null, null));
        WorkerCampaignPlanCompletionRequest request = new WorkerCampaignPlanCompletionRequest(
                "machine-1", fixture.plan.getId(), "Title", "Angle", onlyOne);

        CampaignContentPlanSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(summary.status()).isEqualTo(CampaignPlanStatus.FAILED);
        assertThat(savedItems).isEmpty();
    }

    @Test
    void rejectsWhenAnUnknownOutputIdIsReferenced() {
        WorkerFixture fixture = aiPlanInFlight(1);
        List<WorkerCampaignPlanItemRequest> invented = List.of(
                new WorkerCampaignPlanItemRequest(UUID.randomUUID(), "STANDALONE", "hook", "caption", null, null));
        WorkerCampaignPlanCompletionRequest request = new WorkerCampaignPlanCompletionRequest(
                "machine-1", fixture.plan.getId(), "Title", "Angle", invented);

        CampaignContentPlanSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(summary.status()).isEqualTo(CampaignPlanStatus.FAILED);
        assertThat(savedItems).isEmpty();
    }

    @Test
    void rejectsWhenTheSameOutputIsReferencedTwice() {
        WorkerFixture fixture = aiPlanInFlight(2);
        UUID sameOutput = fixture.outputs.get(0).getId();
        List<WorkerCampaignPlanItemRequest> duplicated = List.of(
                new WorkerCampaignPlanItemRequest(sameOutput, "INTRODUCTION", "hook one", "caption one", null, null),
                new WorkerCampaignPlanItemRequest(sameOutput, "CONCLUSION", "hook two", "caption two", null, null));
        WorkerCampaignPlanCompletionRequest request = new WorkerCampaignPlanCompletionRequest(
                "machine-1", fixture.plan.getId(), "Title", "Angle", duplicated);

        CampaignContentPlanSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(summary.status()).isEqualTo(CampaignPlanStatus.FAILED);
        assertThat(savedItems).isEmpty();
    }

    @Test
    void rejectsAnInvalidRole() {
        WorkerFixture fixture = aiPlanInFlight(1);
        List<WorkerCampaignPlanItemRequest> invalidRole = List.of(
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(0).getId(), "MAIN_CHARACTER", "hook", "caption", null, null));
        WorkerCampaignPlanCompletionRequest request = new WorkerCampaignPlanCompletionRequest(
                "machine-1", fixture.plan.getId(), "Title", "Angle", invalidRole);

        CampaignContentPlanSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(summary.status()).isEqualTo(CampaignPlanStatus.FAILED);
        assertThat(savedItems).isEmpty();
    }

    @Test
    void rejectsAnOversizedHookGuidance() {
        WorkerFixture fixture = aiPlanInFlight(1);
        List<WorkerCampaignPlanItemRequest> oversized = List.of(
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(0).getId(), "STANDALONE",
                        "H".repeat(properties.getMaxHookGuidanceLength() + 1), "caption", null, null));
        WorkerCampaignPlanCompletionRequest request = new WorkerCampaignPlanCompletionRequest(
                "machine-1", fixture.plan.getId(), "Title", "Angle", oversized);

        CampaignContentPlanSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(summary.status()).isEqualTo(CampaignPlanStatus.FAILED);
        assertThat(savedItems).isEmpty();
    }

    @Test
    void rejectsAMissingRequiredCaption() {
        WorkerFixture fixture = aiPlanInFlight(1);
        List<WorkerCampaignPlanItemRequest> blankCaption = List.of(
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(0).getId(), "STANDALONE", "hook", "  ", null, null));
        WorkerCampaignPlanCompletionRequest request = new WorkerCampaignPlanCompletionRequest(
                "machine-1", fixture.plan.getId(), "Title", "Angle", blankCaption);

        CampaignContentPlanSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(summary.status()).isEqualTo(CampaignPlanStatus.FAILED);
        assertThat(savedItems).isEmpty();
    }

    @Test
    void rejectsNearDuplicateHooksAcrossOutputsRatherThanTrustingTheModel() {
        WorkerFixture fixture = aiPlanInFlight(2);
        List<WorkerCampaignPlanItemRequest> nearDuplicateHooks = List.of(
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(0).getId(), "INTRODUCTION",
                        "Check this out right now", "caption one", null, null),
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(1).getId(), "CONCLUSION",
                        "Check this out right now today", "caption two", null, null));
        WorkerCampaignPlanCompletionRequest request = new WorkerCampaignPlanCompletionRequest(
                "machine-1", fixture.plan.getId(), "Title", "Angle", nearDuplicateHooks);

        CampaignContentPlanSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(summary.status()).isEqualTo(CampaignPlanStatus.FAILED);
        assertThat(savedItems).isEmpty();
    }

    @Test
    void distinctHooksAcrossOutputsAreAccepted() {
        WorkerFixture fixture = aiPlanInFlight(2);
        List<WorkerCampaignPlanItemRequest> distinctHooks = List.of(
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(0).getId(), "INTRODUCTION",
                        "Open with the big reveal", "caption one", null, null),
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(1).getId(), "CONCLUSION",
                        "Close out with the final lesson learned", "caption two", null, null));
        WorkerCampaignPlanCompletionRequest request = new WorkerCampaignPlanCompletionRequest(
                "machine-1", fixture.plan.getId(), "Title", "Angle", distinctHooks);

        CampaignContentPlanSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(summary.status()).isEqualTo(CampaignPlanStatus.READY_FOR_REVIEW);
    }

    @Test
    void malformedResponseFailureIsAllOrNothingNoPartialItemsPersisted() {
        WorkerFixture fixture = aiPlanInFlight(3);
        List<WorkerCampaignPlanItemRequest> firstTwoValidThirdMissing = List.of(
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(0).getId(), "INTRODUCTION", "hook a", "caption a", null, null),
                new WorkerCampaignPlanItemRequest(fixture.outputs.get(1).getId(), "DEEP_DIVE", "hook b", "caption b", null, null));
        WorkerCampaignPlanCompletionRequest request = new WorkerCampaignPlanCompletionRequest(
                "machine-1", fixture.plan.getId(), "Title", "Angle", firstTwoValidThirdMissing);

        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(savedItems).isEmpty();
    }

    @Test
    void failWorkerGenerationMarksThePlanFailedOnTerminalFailure() {
        WorkerFixture fixture = aiPlanInFlight(1);
        WorkerCampaignPlanFailureRequest failure = new WorkerCampaignPlanFailureRequest(
                "machine-1", fixture.plan.getId(), "AI_PROVIDER_UNAVAILABLE", "provider down", true);

        CampaignContentPlanSummary summary = service.failWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), failure);

        assertThat(summary.status()).isEqualTo(CampaignPlanStatus.FAILED);
        assertThat(summary.failureCode()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
    }

    @Test
    void authorizeWorkerGenerationNeverExposesPerformanceOrAnalyticsLanguage() {
        WorkerFixture fixture = aiPlanInFlight(2);

        WorkerCampaignPlanAuthorizationResponse authorization =
                service.authorizeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), "machine-1");

        assertThat(authorization.expectedOutputIds()).containsExactly(
                fixture.outputs.get(0).getId(), fixture.outputs.get(1).getId());
        String lower = authorization.prompt().toLowerCase();
        assertThat(lower).doesNotContain("view count", "likes", "shares", "engagement rate");
    }

    // ---- fixtures ----

    private RobotRun newRun() {
        return new RobotRun(workspace, robot, RobotRunTriggerType.MANUAL, null, NOW);
    }

    private RobotRunOutput output(int selectionOrder) {
        RobotRunOutput out = mock(RobotRunOutput.class);
        UUID id = UUID.randomUUID();
        when(out.getId()).thenReturn(id);
        when(out.getSelectionOrder()).thenReturn(selectionOrder);
        HighlightCandidate candidate = mock(HighlightCandidate.class);
        when(candidate.getId()).thenReturn(UUID.randomUUID());
        when(candidate.getStartMs()).thenReturn((long) selectionOrder * 1000);
        when(candidate.getEndMs()).thenReturn((long) selectionOrder * 1000 + 5000);
        when(candidate.getScore()).thenReturn(new BigDecimal("0.9"));
        when(candidate.getReason()).thenReturn("reason " + selectionOrder);
        when(candidate.getTranscriptExcerpt()).thenReturn("transcript excerpt " + selectionOrder);
        when(out.getCandidate()).thenReturn(candidate);
        return out;
    }

    private JobSummary jobSummary(Job job) {
        return new JobSummary(
                job.getId(), job.getType(), job.getStatus(), job.getPayload(), job.getResult(),
                job.getErrorCode(), job.getErrorMessage(), null, null, job.getAttemptCount(), job.getMaxAttempts(),
                job.getQueuedAt(), job.getAssignedAt(), job.getStartedAt(), job.getFinishedAt(),
                job.getLeaseExpiresAt(), job.getCreatedAt(), job.getUpdatedAt());
    }

    private WorkerRegistrationRequest registration() {
        return new WorkerRegistrationRequest(
                "machine-1", "Node A", "Windows 11", "amd64", "AMD Ryzen", 16, 34_359_738_368L, null, null, "fdm-worker/0.1.0");
    }

    private record WorkerFixture(
            RobotRun run, List<RobotRunOutput> outputs, CampaignContentPlan plan, Job job, WorkerPrincipal workerPrincipal) {
    }

    private WorkerFixture aiPlanInFlight(int outputCount) {
        return aiPlanInFlight(outputCount, CampaignPlanningPolicy.AI_PLAN_FOR_REVIEW);
    }

    private WorkerFixture aiPlanInFlight(int outputCount, CampaignPlanningPolicy policy) {
        when(robot.getCampaignPlanningPolicy()).thenReturn(policy);
        RobotRun run = newRun();
        List<RobotRunOutput> outputs = new ArrayList<>();
        for (int i = 1; i <= outputCount; i++) {
            outputs.add(output(i));
        }
        when(outputsRepository.findByRobotRunOrderBySelectionOrderAsc(run)).thenReturn(outputs);
        Job generationJob = new Job(workspace, JobType.GENERATE_CAMPAIGN_PLAN, Map.of(), 3, NOW);
        when(jobService.createForWorkspace(any(), any())).thenReturn(jobSummary(generationJob));
        when(jobService.getJobEntityForWorkspace(workspace, generationJob.getId())).thenReturn(Optional.of(generationJob));

        service.advance(run, outputs, null, NOW);
        CampaignContentPlan plan = savedPlans.get(savedPlans.size() - 1);

        WorkerCredential credential = new WorkerCredential(UUID.randomUUID(), workspace, "local-agent", "$2a$10$hash");
        Worker worker = new Worker(workspace, credential, registration(), NOW);
        WorkerPrincipal workerPrincipal = new WorkerPrincipal(credential);
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, generationJob.getId())).thenReturn(generationJob);
        generationJob.claim(worker, NOW.minusSeconds(1), NOW.plusSeconds(30));
        generationJob.start(worker, NOW, NOW.plusSeconds(30));

        return new WorkerFixture(run, outputs, plan, generationJob, workerPrincipal);
    }
}
