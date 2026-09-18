package com.fdmultimedia.api.jobs;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SchedulingDecisionRetention {
    private static final Logger log = LoggerFactory.getLogger(SchedulingDecisionRetention.class);

    private final SchedulingDecisionRepository decisions;
    private final SchedulingObservabilityProperties properties;
    private final Clock clock;

    public SchedulingDecisionRetention(
            SchedulingDecisionRepository decisions,
            SchedulingObservabilityProperties properties,
            Clock clock) {
        this.decisions = decisions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.scheduling.observability.cleanup-cron:0 17 3 * * *}")
    @Transactional
    public void cleanup() {
        try {
            Instant threshold = Instant.now(clock).minus(properties.safeDecisionRetentionDays(), ChronoUnit.DAYS);
            int deleted = decisions.deleteBatchBefore(threshold, 10_000);
            if (deleted > 0) {
                log.info("Deleted {} expired scheduling decision records", deleted);
            }
        } catch (RuntimeException ex) {
            log.warn("Scheduling decision cleanup failed", ex);
        }
    }
}
