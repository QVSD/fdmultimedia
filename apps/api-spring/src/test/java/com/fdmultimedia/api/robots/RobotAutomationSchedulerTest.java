package com.fdmultimedia.api.robots;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RobotAutomationSchedulerTest {

    private final RobotAutomationDispatchService dispatchService = mock(RobotAutomationDispatchService.class);
    private final RobotRunOrchestrator orchestrator = mock(RobotRunOrchestrator.class);
    private final RobotRunRepository runs = mock(RobotRunRepository.class);
    private final RobotProperties properties = new RobotProperties();
    private final RobotAutomationScheduler scheduler = new RobotAutomationScheduler(dispatchService, orchestrator, runs, properties);

    @Test
    void pollDoesNothingWhenAutomationDisabled() {
        properties.setAutomationEnabled(false);

        scheduler.poll();

        verify(dispatchService, never()).dispatchOne();
        verify(runs, never()).findNonTerminalIds(anyInt());
    }

    @Test
    void pollStartsDueRunsUpToBatchSizeThenStops() {
        properties.setDispatchBatchSize(3);
        when(dispatchService.dispatchOne()).thenReturn(true, true, true, true);
        when(runs.findNonTerminalIds(anyInt())).thenReturn(List.of());

        scheduler.poll();

        verify(dispatchService, times(3)).dispatchOne();
    }

    @Test
    void pollStopsDispatchEarlyWhenNothingDue() {
        when(dispatchService.dispatchOne()).thenReturn(false);
        when(runs.findNonTerminalIds(anyInt())).thenReturn(List.of());

        scheduler.poll();

        verify(dispatchService, times(1)).dispatchOne();
    }

    @Test
    void pollReconcilesEachNonTerminalRunAndOneFailureDoesNotStopTheBatch() {
        when(dispatchService.dispatchOne()).thenReturn(false);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(runs.findNonTerminalIds(anyInt())).thenReturn(List.of(a, b));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(orchestrator).reconcileOne(a);

        scheduler.poll();

        verify(orchestrator).reconcileOne(a);
        verify(orchestrator).reconcileOne(b);
    }
}
