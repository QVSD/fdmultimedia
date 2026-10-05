package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.opscontrol.OpsFacts.*;
import com.fdmultimedia.api.opscontrol.OpsModels.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-mostly persistence port of the operations control plane. Every method is bounded and workspace-scoped unless stated. */
public interface OpsStore {
    int MAX_WORKERS = 500;
    int MAX_PAGE = 100;
    int MAX_ACTIVE_INCIDENTS = 200;
    int MAX_ROLLBACK_INCIDENTS = 20;

    // ---- platform-level (no workspace data) ----
    List<SchedulerInstance> schedulerInstances();
    int purgeSchedulerStatus(Instant before);
    List<UUID> workspaceIds(int limit);
    /** Transaction-scoped try-lock: true when this caller is the only one running {@code key}. Requires an open transaction. */
    boolean tryAdvisoryLock(String key);

    // ---- workspace-scoped facts ----
    List<WorkerFact> workers(UUID workspaceId, Instant recentSince);
    JobFacts jobFacts(UUID workspaceId, Instant now, Instant since, Instant burstSince);
    Page<JobRow> jobs(UUID workspaceId, String status, String type, int page, int size);
    Optional<JobDetail> job(UUID workspaceId, UUID jobId);
    PublishingFacts publishingFacts(UUID workspaceId, Instant now, Instant since, Instant burstSince, Instant overdueBefore);
    List<ProviderFact> providers(UUID workspaceId, Instant since);
    Page<PublicationRow> publicationAttention(UUID workspaceId, Instant since, int page, int size);
    AutomationFacts automationFacts(UUID workspaceId, Instant now, Instant since);
    List<RollbackFact> openRollbackRecommendations(UUID workspaceId, int limit);

    // ---- incidents ----
    List<Incident> activeIncidents(UUID workspaceId);
    Page<Incident> incidents(UUID workspaceId, String status, Severity severity, int page, int size);
    Optional<Incident> incident(UUID workspaceId, UUID incidentId);
    /** Inserts an ACTIVE incident unless one with the same key is already active; true when inserted. */
    boolean insertIncident(UUID workspaceId, DerivedIncident incident, Instant now);
    void updateIncident(UUID incidentId, DerivedIncident incident, Instant now);
    void touchIncident(UUID incidentId, Instant now);
    void resolveIncident(UUID incidentId, Instant now);
    /** Marks an ACTIVE, unacknowledged incident seen; false when it was not in that state. */
    boolean acknowledgeIncident(UUID workspaceId, UUID incidentId, UUID userId, Instant now);
    int purgeResolvedIncidents(Instant before);
}
