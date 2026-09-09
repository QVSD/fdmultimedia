package com.fdmultimedia.api.workers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WorkerStatusServiceTest {

    private final Instant now = Instant.parse("2026-09-09T12:00:00Z");
    private final WorkerProperties properties = new WorkerProperties();
    private final WorkerStatusService service =
            new WorkerStatusService(properties, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void derivesOnlineStatusWithinOfflineThreshold() {
        properties.setOfflineThreshold(Duration.ofSeconds(30));

        WorkerStatus status = service.statusFor(now.minusSeconds(29));

        assertThat(status).isEqualTo(WorkerStatus.ONLINE);
    }

    @Test
    void derivesOfflineStatusAfterOfflineThreshold() {
        properties.setOfflineThreshold(Duration.ofSeconds(30));

        WorkerStatus status = service.statusFor(now.minusSeconds(31));

        assertThat(status).isEqualTo(WorkerStatus.OFFLINE);
    }
}
