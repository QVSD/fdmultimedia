package com.fdmultimedia.api.analytics;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PublicationAnalyticsScheduler {
    private final PublicationAnalyticsService service;

    public PublicationAnalyticsScheduler(PublicationAnalyticsService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.publication-analytics.poll-interval-ms:60000}")
    public void run() {
        service.collectDueBatch();
    }
}
