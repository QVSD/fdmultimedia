package com.fdmultimedia.api.publishschedules;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class PublishScheduleDispatcherTest {

    private final PublishScheduleDispatchService dispatchService = mock(PublishScheduleDispatchService.class);
    private final PublishScheduleProperties properties = new PublishScheduleProperties();
    private final PublishScheduleDispatcher dispatcher = new PublishScheduleDispatcher(dispatchService, properties);

    @Test
    void pollAndDispatchStopsAtBatchSizeEvenWhenMoreMightBeDue() {
        properties.setDispatchBatchSize(3);
        when(dispatchService.dispatchOne()).thenReturn(true, true, true, true);

        dispatcher.pollAndDispatch();

        // Bounded batch: stops after exactly batchSize claims within one poll
        // cycle, leaving anything beyond that for the next cycle.
        verify(dispatchService, times(3)).dispatchOne();
    }

    @Test
    void pollAndDispatchStopsEarlyWhenNothingDue() {
        when(dispatchService.dispatchOne()).thenReturn(false);

        dispatcher.pollAndDispatch();

        verify(dispatchService, times(1)).dispatchOne();
    }

    @Test
    void pollAndDispatchDoesNothingWhenDisabled() {
        properties.setEnabled(false);

        dispatcher.pollAndDispatch();

        verify(dispatchService, never()).dispatchOne();
    }

    @Test
    void oneUnexpectedFailureEndsTheCurrentPollCycleWithoutPropagating() {
        when(dispatchService.dispatchOne()).thenThrow(new RuntimeException("boom"));

        dispatcher.pollAndDispatch();

        verify(dispatchService, times(1)).dispatchOne();
    }
}
