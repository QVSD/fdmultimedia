package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.opscontrol.OpsModels.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Incident lifecycle. Incidents are derived; this service only records the first observation of a condition, refreshes it at most once
 * per {@link #REFRESH_INTERVAL}, resolves it when the condition disappears, and keeps the acknowledgement (an operator has seen it,
 * which is not the same as fixed). A condition that returns after being resolved opens a new incident.
 */
@Service
public class OpsIncidentService {
    public static final Duration REFRESH_INTERVAL = Duration.ofSeconds(60);
    private static final Logger log = LoggerFactory.getLogger(OpsIncidentService.class);

    private final OpsStore store;
    private final Clock clock;

    public OpsIncidentService(OpsStore store, Clock clock) { this.store = store; this.clock = clock; }

    /** Brings the persisted incidents of a workspace in line with the derived set and returns the resulting active incidents. */
    @Transactional
    public List<Incident> sync(UUID workspaceId, List<DerivedIncident> derived) {
        Instant now = Instant.now(clock);
        List<Incident> active = store.activeIncidents(workspaceId);
        Map<String, Incident> byKey = new HashMap<>();
        active.forEach(i -> byKey.put(i.key(), i));
        boolean changed = false;
        for (DerivedIncident d : derived) {
            Incident existing = byKey.remove(d.key());
            if (existing == null) {
                changed |= store.insertIncident(workspaceId, d, now);
            } else if (materiallyChanged(existing, d)
                    || Duration.between(existing.lastObservedAt(), now).compareTo(REFRESH_INTERVAL) >= 0) {
                store.updateIncident(existing.id(), d, now);
                changed = true;
            }
        }
        for (Incident gone : byKey.values()) {
            store.resolveIncident(gone.id(), now);
            changed = true;
        }
        return changed ? store.activeIncidents(workspaceId) : active;
    }

    private static boolean materiallyChanged(Incident e, DerivedIncident d) {
        return e.severity() != d.severity() || !Objects.equals(e.title(), d.title()) || !Objects.equals(e.conditionCode(), d.conditionCode())
                || !Objects.equals(e.subjectType(), d.subjectType()) || !Objects.equals(e.subjectId(), d.subjectId())
                || !Objects.equals(e.suggestedAction(), d.suggestedAction());
    }

    @Transactional(readOnly = true)
    public Page<Incident> list(UUID workspaceId, String status, Severity severity, int page, int size) {
        return store.incidents(workspaceId, status, severity, page, size);
    }

    @Transactional
    public Incident acknowledge(UUID workspaceId, UUID userId, UUID incidentId) {
        Instant now = Instant.now(clock);
        Incident incident = store.incident(workspaceId, incidentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
        if (incident.status() == IncidentStatus.RESOLVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Incident is already resolved");
        }
        if (incident.acknowledged()) return incident;
        if (store.acknowledgeIncident(workspaceId, incidentId, userId, now)) {
            log.info("Operations incident acknowledged workspaceId={} incidentKey={} userId={}", workspaceId, incident.key(), userId);
        }
        return store.incident(workspaceId, incidentId).orElse(incident);
    }

    /** Housekeeping, bounded by two indexed deletes. */
    @Transactional
    public int purge(Duration resolvedRetention, Duration schedulerRetention) {
        Instant now = Instant.now(clock);
        return store.purgeResolvedIncidents(now.minus(resolvedRetention)) + store.purgeSchedulerStatus(now.minus(schedulerRetention));
    }
}
