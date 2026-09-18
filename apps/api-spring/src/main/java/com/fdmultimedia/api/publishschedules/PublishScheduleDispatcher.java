package com.fdmultimedia.api.publishschedules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Central, server-owned dispatch of due {@link PublishSchedule}s. A future
 * schedule reserves no Worker, Job lease, or {@code PUBLISH_MEDIA} Job — this
 * is the one thing that turns a due schedule into a normal Publication
 * (through the existing {@code PublishingService}, via
 * {@link PublishScheduleDispatchService}), at which point the existing
 * distributed Job/Worker pipeline takes over exactly as it does for an
 * immediate publish.
 *
 * <p>Each due schedule is claimed and fully processed in its own short
 * transaction ({@link PublishScheduleDispatchService#dispatchOne()}), using
 * the same {@code SELECT ... FOR UPDATE SKIP LOCKED} idiom
 * {@code JobRepository} already uses for atomic Job claiming: two API
 * instances polling concurrently can never both claim the same row, and a
 * crash between claim and commit simply leaves the schedule
 * {@code SCHEDULED} for the next poll to pick up. Every dispatch outcome
 * (including truly unexpected errors) ends by moving the schedule to a
 * terminal state so one broken schedule can never block the rest of the
 * batch, and so a busy day never retries a doomed schedule forever.
 */
@Component
public class PublishScheduleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PublishScheduleDispatcher.class);

    private final PublishScheduleDispatchService dispatchService;
    private final PublishScheduleProperties properties;

    public PublishScheduleDispatcher(PublishScheduleDispatchService dispatchService, PublishScheduleProperties properties) {
        this.dispatchService = dispatchService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.publishing.schedule.poll-interval-ms:15000}")
    public void pollAndDispatch() {
        if (!properties.isEnabled()) {
            return;
        }
        int processed = 0;
        int batchSize = Math.max(1, properties.getDispatchBatchSize());
        while (processed < batchSize) {
            boolean didWork;
            try {
                didWork = dispatchService.dispatchOne();
            } catch (RuntimeException ex) {
                log.error("Publish schedule dispatch cycle failed unexpectedly", ex);
                break;
            }
            if (!didWork) {
                break;
            }
            processed++;
        }
    }
}
