package com.fdmultimedia.api.robots;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import com.fdmultimedia.api.workspaces.Workspace;
import org.junit.jupiter.api.Test;

class RobotMultiOutputOrchestratorTest {
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private final RobotMultiOutputOrchestrator orchestrator = new RobotMultiOutputOrchestrator(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void allSuccessfulOutputsCompleteTheParent() {
        RobotRun run = mock(RobotRun.class);
        orchestrator.aggregate(run, List.of(output(RobotRunOutputStatus.SUCCEEDED),
                output(RobotRunOutputStatus.SUCCEEDED)), NOW);
        verify(run).markSucceeded(NOW);
    }

    @Test
    void mixedTerminalOutputsProducePartialSuccess() {
        RobotRun run = mock(RobotRun.class);
        orchestrator.aggregate(run, List.of(output(RobotRunOutputStatus.SUCCEEDED),
                output(RobotRunOutputStatus.FAILED)), NOW);
        verify(run).markPartiallySucceeded(NOW);
        verify(run, never()).markSucceeded(NOW);
    }

    @Test
    void allFailedOutputsFailTheParent() {
        RobotRun run = mock(RobotRun.class);
        orchestrator.aggregate(run, List.of(output(RobotRunOutputStatus.FAILED),
                output(RobotRunOutputStatus.CANCELLED)), NOW);
        verify(run).markFailed("ALL_OUTPUTS_FAILED", "No output completed successfully", NOW);
    }

    @Test
    void activeSiblingKeepsParentActive() {
        RobotRun run = mock(RobotRun.class);
        orchestrator.aggregate(run, List.of(output(RobotRunOutputStatus.SUCCEEDED),
                output(RobotRunOutputStatus.WAITING_FOR_DRAFT)), NOW);
        verify(run, never()).markSucceeded(NOW);
        verify(run, never()).markPartiallySucceeded(NOW);
        verify(run, never()).markFailed("ALL_OUTPUTS_FAILED", "No output completed successfully", NOW);
    }

    @Test
    void scheduleBaseIsFrozenAcrossLaterReconciliation() {
        Robot robot = mock(Robot.class);
        when(robot.getAiPolicy()).thenReturn(RobotAiPolicy.NO_AI);
        when(robot.getHighlightStrategy()).thenReturn(RobotHighlightStrategy.TOP_DIVERSE_HIGHLIGHTS);
        when(robot.getHighlightCount()).thenReturn(3);
        when(robot.getOutputSpacingMinutes()).thenReturn(60);
        RobotRun run = new RobotRun(mock(Workspace.class), robot, RobotRunTriggerType.MANUAL, null, NOW);

        Instant base = run.outputScheduleBase(NOW, 15);

        org.assertj.core.api.Assertions.assertThat(base).isEqualTo(NOW.plusSeconds(900));
        org.assertj.core.api.Assertions.assertThat(run.outputScheduleBase(NOW.plusSeconds(300), 15)).isEqualTo(base);
        org.assertj.core.api.Assertions.assertThat(base.plusSeconds(2 * 3600))
                .isEqualTo(run.getOutputScheduleBaseAt().plusSeconds(2 * 3600));
    }

    private RobotRunOutput output(RobotRunOutputStatus status) {
        RobotRunOutput output = mock(RobotRunOutput.class);
        when(output.getStatus()).thenReturn(status);
        return output;
    }
}
