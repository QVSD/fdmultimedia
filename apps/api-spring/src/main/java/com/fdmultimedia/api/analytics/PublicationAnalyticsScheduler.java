package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PublicationAnalyticsScheduler {
    private final PublicationAnalyticsService service;
    private final SchedulerOperationTracker operations;

    public PublicationAnalyticsScheduler(PublicationAnalyticsService service, SchedulerOperationTracker operations) {
        this.service = service;
        this.operations = operations;
    }

    @Scheduled(fixedDelayString = "${app.publication-analytics.poll-interval-ms:60000}")
    public void run() {
        operations.run(SchedulerOperationTracker.PUBLICATION_ANALYTICS, () -> {
            int processed = service.collectDueBatch();
            return new SchedulerOperationTracker.Outcome(processed, processed);
        });
    }
}
