package com.fdmultimedia.api.shared.health;

import com.fdmultimedia.api.assets.ObjectStorageService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Component health only. MinIO is intentionally excluded from the readiness
 * group so metadata-only API paths remain available during storage outages.
 */
@Component("objectStorage")
public class ObjectStorageHealthIndicator implements HealthIndicator {
    private final ObjectStorageService storage;

    public ObjectStorageHealthIndicator(ObjectStorageService storage) {
        this.storage = storage;
    }

    @Override
    public Health health() {
        return storage.isAvailable()
                ? Health.up().build()
                : Health.down().withDetail("reason", "unavailable").build();
    }
}
