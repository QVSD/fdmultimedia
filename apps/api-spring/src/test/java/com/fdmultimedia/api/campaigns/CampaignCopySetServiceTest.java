package com.fdmultimedia.api.campaigns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.fdmultimedia.api.robots.CopyCoordinationPolicy;
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
import org.springframework.web.server.ResponseStatusException;

class CampaignCopySetServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final RobotRunRepository runs = mock(RobotRunRepository.class);
    private final RobotRunOutputRepository outputsRepository = mock(RobotRunOutputRepository.class);
    private final CampaignContentPlanRepository plans = mock(CampaignContentPlanRepository.class);
    private final CampaignContentPlanItemRepository planItems = mock(CampaignContentPlanItemRepository.class);
    private final CampaignCopySetRepository copySets = mock(CampaignCopySetRepository.class);
    private final CampaignCopyItemRepository copyItems = mock(CampaignCopyItemRepository.class);
    private final CoordinatedCopyPromptBuilder promptBuilder = new CoordinatedCopyPromptBuilder();
    private final CampaignRepetitionDetector repetitionDetector = new CampaignRepetitionDetector();
    private final CampaignCopyProperties properties = new CampaignCopyProperties();
    private final ContentAiProperties aiProperties = new ContentAiProperties();
    private final PersonaRepository personas = mock(PersonaRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final CampaignCopySetService service = new CampaignCopySetService(
            authService, runs, outputsRepository, plans, planItems, copySets, copyItems, promptBuilder,
            repetitionDetector, properties, aiProperties, personas, jobService, Clock.fixed(NOW, ZoneOffset.UTC));

    private final List<CampaignContentPlan> savedPlans = new ArrayList<>();
    private final List<CampaignContentPlanItem> savedPlanItems = new ArrayList<>();
    private final List<CampaignCopySet> savedCopySets = new ArrayList<>();
    private final List<CampaignCopyItem> savedCopyItems = new ArrayList<>();

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
        when(robot.getCopyCoordinationPolicy()).thenReturn(CopyCoordinationPolicy.COORDINATED_COPY_FOR_REVIEW);
        when(robot.getCreatedByUser()).thenReturn(owner);

        when(plans.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return savedPlans.stream().filter(p -> p.getId().equals(id)).findFirst();
        });
        when(planItems.findByPlanOrderBySequenceAsc(any())).thenAnswer(inv -> {
            CampaignContentPlan plan = inv.getArgument(0);
            return savedPlanItems.stream()
                    .filter(i -> i.getPlan().getId().equals(plan.getId()))
                    .sorted(Comparator.comparingInt(CampaignContentPlanItem::getSequence))
                    .toList();
        });

        when(copySets.save(any(CampaignCopySet.class))).thenAnswer(inv -> {
            CampaignCopySet copySet = inv.getArgument(0);
            savedCopySets.add(copySet);
            return copySet;
        });
        when(copySets.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return savedCopySets.stream().filter(c -> c.getId().equals(id)).findFirst();
        });
        when(copySets.findByRobotRunOrderByRevisionDesc(any())).thenAnswer(inv -> {
            RobotRun run = inv.getArgument(0);
            return savedCopySets.stream()
                    .filter(c -> c.getRobotRun().getId().equals(run.getId()))
                    .sorted(Comparator.comparingInt(CampaignCopySet::getRevision).reversed())
                    .toList();
        });
        when(copySets.findByGenerationJobId(any(UUID.class))).thenAnswer(inv -> {
            UUID jobId = inv.getArgument(0);
            return savedCopySets.stream()
                    .filter(c -> c.getGenerationJob() != null && c.getGenerationJob().getId().equals(jobId))
                    .findFirst();
        });
        when(copySets.findByWorkspaceAndId(any(), any())).thenAnswer(inv -> {
            Workspace ws = inv.getArgument(0);
            UUID id = inv.getArgument(1);
            return savedCopySets.stream().filter(c -> c.getWorkspace().equals(ws) && c.getId().equals(id)).findFirst();
        });

        when(copyItems.saveAll(any())).thenAnswer(inv -> {
            List<CampaignCopyItem> list = inv.getArgument(0);
            savedCopyItems.addAll(list);
            return list;
        });
        when(copyItems.findByCopySetOrderBySequenceAsc(any())).thenAnswer(inv -> {
            CampaignCopySet copySet = inv.getArgument(0);
            return savedCopyItems.stream()
                    .filter(i -> i.getCopySet().getId().equals(copySet.getId()))
                    .sorted(Comparator.comparingInt(CampaignCopyItem::getSequence))
                    .toList();
        });
        when(copyItems.findByCopySetAndRobotRunOutputId(any(), any())).thenAnswer(inv -> {
            CampaignCopySet copySet = inv.getArgument(0);
            UUID outputId = inv.getArgument(1);
            return savedCopyItems.stream()
                    .filter(i -> i.getCopySet().getId().equals(copySet.getId()) && i.getRobotRunOutputId().equals(outputId))
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

    // ---- advance() gating on an applied campaign plan ----

    @Test
    void independentCopyPolicyIsANoOp() {
        when(robot.getCopyCoordinationPolicy()).thenReturn(CopyCoordinationPolicy.INDEPENDENT_COPY);
        RobotRun run = newRun();

        service.advance(run, List.of(output(1)), null, NOW);

        assertThat(run.getCampaignCopySetId()).isNull();
        assertThat(savedCopySets).isEmpty();
        assertThat(service.isBlockingAiGeneration(run)).isFalse();
    }

    @Test
    void advanceIsANoOpUntilAnAppliedCampaignPlanExists() {
        RobotRun run = newRun();

        service.advance(run, List.of(output(1)), null, NOW);

        assertThat(run.getCampaignCopySetId()).isNull();
        assertThat(savedCopySets).isEmpty();
        assertThat(service.isBlockingAiGeneration(run)).isTrue();
    }

    @Test
    void advanceCreatesACopySetOnceTheCampaignPlanIsApplied() {
        RobotRun run = newRun();
        List<RobotRunOutput> outputs = List.of(output(1), output(2), output(3));
        appliedPlan(run, outputs);
        stubJobDispatch();

        service.advance(run, outputs, null, NOW);

        assertThat(savedCopySets).hasSize(1);
        assertThat(run.getCampaignCopySetId()).isEqualTo(savedCopySets.get(0).getId());
    }

    @Test
    void advanceIsIdempotentAcrossMultipleReconcilerPasses() {
        RobotRun run = newRun();
        List<RobotRunOutput> outputs = List.of(output(1));
        appliedPlan(run, outputs);
        stubJobDispatch();

        service.advance(run, outputs, null, NOW);
        service.advance(run, outputs, null, NOW);
        service.advance(run, outputs, null, NOW);

        assertThat(savedCopySets).hasSize(1);
    }

    @Test
    void aiDisabledFailsTheCopySetWithoutDispatchingAJob() {
        aiProperties.setEnabled(false);
        RobotRun run = newRun();
        List<RobotRunOutput> outputs = List.of(output(1));
        appliedPlan(run, outputs);

        service.advance(run, outputs, null, NOW);

        assertThat(savedCopySets.get(0).getStatus()).isEqualTo(CampaignCopySetStatus.FAILED);
        assertThat(savedCopySets.get(0).getFailureCode()).isEqualTo("CAMPAIGN_COPY_AI_DISABLED");
        assertThat(service.blockedFailureCode(run)).contains("CAMPAIGN_COPY_GENERATION_FAILED");
        verify(jobService, never()).createForWorkspace(any(), any());
    }

    @Test
    void dispatchesAGenerationJobAndBlocksAiGenerationUntilApplied() {
        RobotRun run = newRun();
        List<RobotRunOutput> outputs = List.of(output(1), output(2));
        appliedPlan(run, outputs);
        Job generationJob = new Job(workspace, JobType.GENERATE_COORDINATED_SOCIAL_COPY, Map.of(), 3, NOW);
        when(jobService.createForWorkspace(any(), any())).thenReturn(jobSummary(generationJob));
        when(jobService.getJobEntityForWorkspace(workspace, generationJob.getId())).thenReturn(Optional.of(generationJob));

        service.advance(run, outputs, null, NOW);

        assertThat(run.getCampaignCopySetId()).isNotNull();
        assertThat(service.isBlockingAiGeneration(run)).isTrue();
        assertThat(savedCopyItems).isEmpty();
    }

    @Test
    void findItemForIsEmptyBeforeApplyAndPopulatedAfter() {
        WorkerFixture fixture = coordinatedCopyInFlight(1);
        List<WorkerCoordinatedCopyItemRequest> items = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "hook one", "caption one", List.of("tagone"), "short", null));
        assertThat(service.findItemFor(fixture.run, fixture.outputs.get(0))).isEmpty();

        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", items));
        // COORDINATED_COPY_FOR_REVIEW does not auto-apply.
        assertThat(service.findItemFor(fixture.run, fixture.outputs.get(0))).isEmpty();

        service.apply(user, fixture.copySet.getId());
        assertThat(service.findItemFor(fixture.run, fixture.outputs.get(0))).isPresent();
    }

    @Test
    void blockedFailureCodeReflectsRejectedAndFailedCopySets() {
        WorkerFixture fixture = coordinatedCopyInFlight(1);
        List<WorkerCoordinatedCopyItemRequest> items = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "hook one", "caption one", List.of("tagone"), "short", null));
        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", items));

        service.reject(user, fixture.copySet.getId());

        assertThat(service.blockedFailureCode(fixture.run)).contains("CAMPAIGN_COPY_REJECTED");
    }

    // ---- structured validation: exact membership, ordering, role mapping ----

    @Test
    void completeWorkerGenerationPersistsExactlyOneItemPerOutputInSelectionOrder() {
        WorkerFixture fixture = coordinatedCopyInFlight(3);
        List<WorkerCoordinatedCopyItemRequest> valid = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "Open with the big reveal", "caption a", List.of("a"), "s1", null),
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(1).getId(), "Dig into the specifics", "caption b", List.of("b"), "s2", "Follows part 1"),
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(2).getId(), "Close out with the takeaway", "caption c", List.of("c"), "s3", "Follows part 2"));
        WorkerCoordinatedCopyCompletionRequest request =
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series title", valid);

        CampaignCopySetSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), request);

        assertThat(summary.status()).isEqualTo(CampaignCopySetStatus.READY_FOR_REVIEW);
        assertThat(savedCopyItems).hasSize(3);
        assertThat(savedCopyItems.stream().map(CampaignCopyItem::getSequence)).containsExactly(1, 2, 3);
        assertThat(savedCopyItems.stream().map(CampaignCopyItem::getRobotRunOutputId))
                .containsExactly(fixture.outputs.get(0).getId(), fixture.outputs.get(1).getId(), fixture.outputs.get(2).getId());
    }

    @Test
    void completeWorkerGenerationAutoAppliesForCoordinatedCopyAndApply() {
        WorkerFixture fixture = coordinatedCopyInFlight(1, CopyCoordinationPolicy.COORDINATED_COPY_AND_APPLY);
        List<WorkerCoordinatedCopyItemRequest> valid = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "Open strong", "caption", List.of("a"), "short", null));

        CampaignCopySetSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", valid));

        assertThat(summary.status()).isEqualTo(CampaignCopySetStatus.APPLIED);
    }

    @Test
    void rejectsWhenAnOutputIsMissingFromTheResponse() {
        WorkerFixture fixture = coordinatedCopyInFlight(2);
        List<WorkerCoordinatedCopyItemRequest> onlyOne = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "hook", "caption", List.of("a"), null, null));

        CampaignCopySetSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", onlyOne));

        assertThat(summary.status()).isEqualTo(CampaignCopySetStatus.FAILED);
        assertThat(summary.failureCode()).isEqualTo("CAMPAIGN_COPY_INVALID_OUTPUT");
        assertThat(savedCopyItems).isEmpty();
    }

    @Test
    void rejectsWhenAnUnknownOutputIdIsReferenced() {
        WorkerFixture fixture = coordinatedCopyInFlight(1);
        List<WorkerCoordinatedCopyItemRequest> invented = List.of(
                new WorkerCoordinatedCopyItemRequest(UUID.randomUUID(), "hook", "caption", List.of("a"), null, null));

        CampaignCopySetSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", invented));

        assertThat(summary.status()).isEqualTo(CampaignCopySetStatus.FAILED);
        assertThat(savedCopyItems).isEmpty();
    }

    @Test
    void rejectsWhenTheSameOutputIsReferencedTwice() {
        WorkerFixture fixture = coordinatedCopyInFlight(2);
        UUID sameOutput = fixture.outputs.get(0).getId();
        List<WorkerCoordinatedCopyItemRequest> duplicated = List.of(
                new WorkerCoordinatedCopyItemRequest(sameOutput, "hook one", "caption one", List.of("a"), null, null),
                new WorkerCoordinatedCopyItemRequest(sameOutput, "hook two", "caption two", List.of("b"), null, null));

        CampaignCopySetSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", duplicated));

        assertThat(summary.status()).isEqualTo(CampaignCopySetStatus.FAILED);
        assertThat(savedCopyItems).isEmpty();
    }

    @Test
    void rejectsNearDuplicateHooksAcrossOutputsRatherThanTrustingTheModel() {
        WorkerFixture fixture = coordinatedCopyInFlight(2);
        List<WorkerCoordinatedCopyItemRequest> nearDuplicate = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "Check this out right now", "caption one", List.of("a"), null, null),
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(1).getId(), "Check this out right now today", "caption two", List.of("b"), null, null));

        CampaignCopySetSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", nearDuplicate));

        assertThat(summary.status()).isEqualTo(CampaignCopySetStatus.FAILED);
        assertThat(savedCopyItems).isEmpty();
    }

    @Test
    void distinctHooksAcrossOutputsAreAccepted() {
        WorkerFixture fixture = coordinatedCopyInFlight(2);
        List<WorkerCoordinatedCopyItemRequest> distinct = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "Open with the big reveal", "caption one talks about the setup", List.of("a"), null, null),
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(1).getId(), "Close out with the final lesson learned", "caption two wraps things up", List.of("b"), null, null));

        CampaignCopySetSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", distinct));

        assertThat(summary.status()).isEqualTo(CampaignCopySetStatus.READY_FOR_REVIEW);
    }

    @Test
    void rejectsRepeatedCaptionOpeningsAcrossOutputs() {
        WorkerFixture fixture = coordinatedCopyInFlight(2);
        String identicalOpening = "This is an incredibly long identical opening sentence that repeats verbatim across both captions no matter what";
        List<WorkerCoordinatedCopyItemRequest> repeatedOpening = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "Distinct hook one", identicalOpening + " and then diverges into the first story.", List.of("a"), null, null),
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(1).getId(), "Totally different hook", identicalOpening + " but ends up somewhere else entirely.", List.of("b"), null, null));

        CampaignCopySetSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", repeatedOpening));

        assertThat(summary.status()).isEqualTo(CampaignCopySetStatus.FAILED);
        assertThat(savedCopyItems).isEmpty();
    }

    @Test
    void rejectsIdenticalHashtagSetsAcrossAllOutputs() {
        WorkerFixture fixture = coordinatedCopyInFlight(2);
        List<WorkerCoordinatedCopyItemRequest> sameTags = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "Open with the big reveal", "caption one about the setup", List.of("shorts", "viral"), null, null),
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(1).getId(), "Close out with the final lesson", "caption two wraps things up", List.of("shorts", "viral"), null, null));

        CampaignCopySetSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", sameTags));

        assertThat(summary.status()).isEqualTo(CampaignCopySetStatus.FAILED);
        assertThat(savedCopyItems).isEmpty();
    }

    @Test
    void distinctHashtagSetsAcrossOutputsAreAccepted() {
        WorkerFixture fixture = coordinatedCopyInFlight(2);
        List<WorkerCoordinatedCopyItemRequest> distinctTags = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "Open with the big reveal", "caption one about the setup", List.of("shorts", "partone"), null, null),
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(1).getId(), "Close out with the final lesson", "caption two wraps things up", List.of("shorts", "parttwo"), null, null));

        CampaignCopySetSummary summary = service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", distinctTags));

        assertThat(summary.status()).isEqualTo(CampaignCopySetStatus.READY_FOR_REVIEW);
    }

    @Test
    void malformedResponseFailureIsAllOrNothingNoPartialItemsPersisted() {
        WorkerFixture fixture = coordinatedCopyInFlight(3);
        List<WorkerCoordinatedCopyItemRequest> firstTwoValidThirdMissing = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "hook a", "caption a", List.of("a"), null, null),
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(1).getId(), "hook b", "caption b", List.of("b"), null, null));

        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", firstTwoValidThirdMissing));

        assertThat(savedCopyItems).isEmpty();
    }

    @Test
    void failWorkerGenerationMarksTheCopySetFailedOnTerminalFailure() {
        WorkerFixture fixture = coordinatedCopyInFlight(1);
        WorkerCoordinatedCopyFailureRequest failure = new WorkerCoordinatedCopyFailureRequest(
                "machine-1", fixture.copySet.getId(), "AI_PROVIDER_UNAVAILABLE", "provider down", true);

        CampaignCopySetSummary summary = service.failWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(), failure);

        assertThat(summary.status()).isEqualTo(CampaignCopySetStatus.FAILED);
        assertThat(summary.failureCode()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
    }

    // ---- apply / reject / regenerate lifecycle ----

    @Test
    void applyIsIdempotentOnAnAlreadyAppliedCopySet() {
        WorkerFixture fixture = coordinatedCopyInFlight(1);
        List<WorkerCoordinatedCopyItemRequest> items = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "hook", "caption", List.of("a"), null, null));
        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", items));

        CampaignCopySetSummary first = service.apply(user, fixture.copySet.getId());
        CampaignCopySetSummary second = service.apply(user, fixture.copySet.getId());

        assertThat(first.status()).isEqualTo(CampaignCopySetStatus.APPLIED);
        assertThat(second.appliedAt()).isEqualTo(first.appliedAt());
    }

    @Test
    void applyFailsSafelyWhenThePinnedPlanRevisionNoLongerMatchesTheRun() {
        WorkerFixture fixture = coordinatedCopyInFlight(1);
        List<WorkerCoordinatedCopyItemRequest> items = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "hook", "caption", List.of("a"), null, null));
        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", items));
        // Simulate the run being re-pinned to a newer plan revision after the copy set was generated.
        fixture.run.bindCampaignPlan(UUID.randomUUID());

        assertThatThrownBy(() -> service.apply(user, fixture.copySet.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CAMPAIGN_COPY_STALE");
    }

    @Test
    void applyFailsSafelyWhenTheUnderlyingOutputSetHasDrifted() {
        WorkerFixture fixture = coordinatedCopyInFlight(2);
        List<WorkerCoordinatedCopyItemRequest> items = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "hook a", "caption a", List.of("a"), null, null),
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(1).getId(), "hook b", "caption b", List.of("b"), null, null));
        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", items));
        when(outputsRepository.findByRobotRunOrderBySelectionOrderAsc(fixture.run)).thenReturn(List.of(fixture.outputs.get(0)));

        assertThatThrownBy(() -> service.apply(user, fixture.copySet.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CAMPAIGN_COPY_STALE");
    }

    @Test
    void applyIsNotAffectedByTheClockAloneOnlyByRealInputChanges() {
        WorkerFixture fixture = coordinatedCopyInFlight(1);
        List<WorkerCoordinatedCopyItemRequest> items = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "hook", "caption", List.of("a"), null, null));
        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", items));

        CampaignCopySetSummary applied = service.apply(user, fixture.copySet.getId());

        assertThat(applied.status()).isEqualTo(CampaignCopySetStatus.APPLIED);
    }

    @Test
    void configSnapshotFingerprintIsIndependentOfMapKeyOrder() {
        // Regression test (found via real Docker/Postgres runtime acceptance,
        // not reproducible with a mocked repository): a jsonb column round trip
        // does not preserve key insertion order, so the same logical config
        // snapshot can come back from the database with its keys in a
        // different order than the in-memory map that was hashed at
        // generation time. The fingerprint must depend only on content.
        Map<String, Object> insertionOrder = new java.util.LinkedHashMap<>();
        insertionOrder.put("promptVersion", "COORDINATED_COPY_V1");
        insertionOrder.put("provider", "OLLAMA");
        insertionOrder.put("model", "llama3.2:latest");
        insertionOrder.put("maxHookLength", 200);
        Map<String, Object> jsonbReorderedEquivalent = new java.util.LinkedHashMap<>();
        jsonbReorderedEquivalent.put("model", "llama3.2:latest");
        jsonbReorderedEquivalent.put("maxHookLength", 200);
        jsonbReorderedEquivalent.put("promptVersion", "COORDINATED_COPY_V1");
        jsonbReorderedEquivalent.put("provider", "OLLAMA");

        assertThat(service.canonicalConfigSnapshot(insertionOrder))
                .isEqualTo(service.canonicalConfigSnapshot(jsonbReorderedEquivalent));
    }

    @Test
    void applyRejectsACopySetThatIsNotReadyForReview() {
        RobotRun run = newRun();
        List<RobotRunOutput> outputs = List.of(output(1));
        appliedPlan(run, outputs);
        Job generationJob = new Job(workspace, JobType.GENERATE_COORDINATED_SOCIAL_COPY, Map.of(), 3, NOW);
        when(jobService.createForWorkspace(any(), any())).thenReturn(jobSummary(generationJob));
        when(jobService.getJobEntityForWorkspace(workspace, generationJob.getId())).thenReturn(Optional.of(generationJob));
        service.advance(run, outputs, null, NOW);
        CampaignCopySet copySet = savedCopySets.get(0);

        assertThatThrownBy(() -> service.apply(user, copySet.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void rejectMarksACopySetRejected() {
        WorkerFixture fixture = coordinatedCopyInFlight(1);
        List<WorkerCoordinatedCopyItemRequest> items = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "hook", "caption", List.of("a"), null, null));
        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", items));

        CampaignCopySetSummary rejected = service.reject(user, fixture.copySet.getId());

        assertThat(rejected.status()).isEqualTo(CampaignCopySetStatus.REJECTED);
    }

    @Test
    void regenerateSupersedesTheCurrentCopySetAndCreatesANewRevision() {
        WorkerFixture fixture = coordinatedCopyInFlight(1);
        List<WorkerCoordinatedCopyItemRequest> items = List.of(
                new WorkerCoordinatedCopyItemRequest(fixture.outputs.get(0).getId(), "hook", "caption", List.of("a"), null, null));
        service.completeWorkerGeneration(fixture.workerPrincipal, fixture.job.getId(),
                new WorkerCoordinatedCopyCompletionRequest("machine-1", fixture.copySet.getId(), "Series", items));
        Job secondJob = new Job(workspace, JobType.GENERATE_COORDINATED_SOCIAL_COPY, Map.of(), 3, NOW);
        when(jobService.createForWorkspace(any(), any())).thenReturn(jobSummary(secondJob));
        when(jobService.getJobEntityForWorkspace(workspace, secondJob.getId())).thenReturn(Optional.of(secondJob));

        CampaignCopySetSummary regenerated = service.regenerate(user, fixture.run.getId());

        assertThat(savedCopySets).hasSize(2);
        assertThat(fixture.copySet.isCurrent()).isFalse();
        assertThat(regenerated.revision()).isEqualTo(2);
        assertThat(regenerated.current()).isTrue();
    }

    @Test
    void regenerateRejectedForIndependentCopyPolicy() {
        when(robot.getCopyCoordinationPolicy()).thenReturn(CopyCoordinationPolicy.INDEPENDENT_COPY);
        RobotRun run = newRun();
        when(runs.findByWorkspaceAndId(workspace, run.getId())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.regenerate(user, run.getId()))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ---- authorization: fixed output set, no performance language ----

    @Test
    void authorizeWorkerGenerationExposesExpectedOutputIdsInSelectionOrder() {
        WorkerFixture fixture = coordinatedCopyInFlight(2);

        WorkerCoordinatedCopyAuthorizationResponse authorization =
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

    private void stubJobDispatch() {
        Job generationJob = new Job(workspace, JobType.GENERATE_COORDINATED_SOCIAL_COPY, Map.of(), 3, NOW);
        when(jobService.createForWorkspace(any(), any())).thenReturn(jobSummary(generationJob));
        when(jobService.getJobEntityForWorkspace(workspace, generationJob.getId())).thenReturn(Optional.of(generationJob));
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

    /** Builds and directly persists (via mocked repositories) an already-APPLIED CampaignContentPlan, bypassing CampaignContentPlanService. */
    private CampaignContentPlan appliedPlan(RobotRun run, List<RobotRunOutput> outputs) {
        CampaignContentPlan plan = new CampaignContentPlan(run, 1, CampaignPlanningPolicy.DETERMINISTIC_PLAN, "V1", "fp", Map.of(), owner, NOW);
        plan.markReadyForReview("Series Title", "Angle", NOW);
        plan.markApplied(owner.getId(), NOW);
        savedPlans.add(plan);
        for (int i = 0; i < outputs.size(); i++) {
            CampaignPlanRole role = outputs.size() == 1 ? CampaignPlanRole.STANDALONE
                    : i == 0 ? CampaignPlanRole.INTRODUCTION
                    : i == outputs.size() - 1 ? CampaignPlanRole.CONCLUSION : CampaignPlanRole.DEEP_DIVE;
            savedPlanItems.add(new CampaignContentPlanItem(plan, outputs.get(i).getId(), outputs.get(i).getSelectionOrder(), role,
                    "hook guidance " + i, "caption guidance " + i, null, null, NOW));
        }
        run.bindCampaignPlan(plan.getId());
        return plan;
    }

    private record WorkerFixture(
            RobotRun run, List<RobotRunOutput> outputs, CampaignCopySet copySet, Job job, WorkerPrincipal workerPrincipal) {
    }

    private WorkerFixture coordinatedCopyInFlight(int outputCount) {
        return coordinatedCopyInFlight(outputCount, CopyCoordinationPolicy.COORDINATED_COPY_FOR_REVIEW);
    }

    private WorkerFixture coordinatedCopyInFlight(int outputCount, CopyCoordinationPolicy policy) {
        when(robot.getCopyCoordinationPolicy()).thenReturn(policy);
        RobotRun run = newRun();
        List<RobotRunOutput> outputs = new ArrayList<>();
        for (int i = 1; i <= outputCount; i++) {
            outputs.add(output(i));
        }
        when(outputsRepository.findByRobotRunOrderBySelectionOrderAsc(run)).thenReturn(outputs);
        appliedPlan(run, outputs);
        Job generationJob = new Job(workspace, JobType.GENERATE_COORDINATED_SOCIAL_COPY, Map.of(), 3, NOW);
        when(jobService.createForWorkspace(any(), any())).thenReturn(jobSummary(generationJob));
        when(jobService.getJobEntityForWorkspace(workspace, generationJob.getId())).thenReturn(Optional.of(generationJob));

        service.advance(run, outputs, null, NOW);
        CampaignCopySet copySet = savedCopySets.get(savedCopySets.size() - 1);

        WorkerCredential credential = new WorkerCredential(UUID.randomUUID(), workspace, "local-agent", "$2a$10$hash");
        Worker worker = new Worker(workspace, credential, registration(), NOW);
        WorkerPrincipal workerPrincipal = new WorkerPrincipal(credential);
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, generationJob.getId())).thenReturn(generationJob);
        generationJob.claim(worker, NOW.minusSeconds(1), NOW.plusSeconds(30));
        generationJob.start(worker, NOW, NOW.plusSeconds(30));

        when(runs.findByWorkspaceAndId(workspace, run.getId())).thenReturn(Optional.of(run));

        return new WorkerFixture(run, outputs, copySet, generationJob, workerPrincipal);
    }
}
