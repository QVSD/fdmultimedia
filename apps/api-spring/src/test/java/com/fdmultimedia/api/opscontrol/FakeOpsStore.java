package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.opscontrol.OpsFacts.*;
import com.fdmultimedia.api.opscontrol.OpsModels.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory OpsStore: facts are plain mutable fields, incidents follow the same lifecycle rules as the SQL constraints. */
class FakeOpsStore implements OpsStore {
    List<SchedulerInstance> schedulerInstances = new ArrayList<>();
    List<UUID> workspaces = new ArrayList<>();
    List<WorkerFact> workers = new ArrayList<>();
    JobFacts jobFacts = new JobFacts(0, 0, 0, 0, 0, 0, 0, null);
    PublishingFacts publishingFacts = new PublishingFacts(0, 0, 0, 0, 0, 0, 0, null);
    List<ProviderFact> providers = new ArrayList<>();
    AutomationFacts automationFacts = new AutomationFacts(0, 0, 0, 0, 0, 0, 0);
    List<RollbackFact> rollbacks = new ArrayList<>();
    List<JobRow> jobRows = new ArrayList<>();
    List<PublicationRow> publications = new ArrayList<>();
    final Map<UUID, Map<UUID, Incident>> incidents = new LinkedHashMap<>();
    final Map<UUID, UUID> acknowledgedBy = new LinkedHashMap<>();
    boolean lockAvailable = true;
    int inserts, updates, resolves;

    private Map<UUID, Incident> of(UUID ws) { return incidents.computeIfAbsent(ws, k -> new LinkedHashMap<>()); }

    @Override public List<SchedulerInstance> schedulerInstances() { return schedulerInstances; }
    @Override public int purgeSchedulerStatus(Instant before) { return 0; }
    @Override public List<UUID> workspaceIds(int limit) { return workspaces.stream().limit(limit).toList(); }
    @Override public boolean tryAdvisoryLock(String key) { return lockAvailable; }
    @Override public List<WorkerFact> workers(UUID workspaceId, Instant recentSince) { return workers; }
    @Override public JobFacts jobFacts(UUID workspaceId, Instant now, Instant since, Instant burstSince) { return jobFacts; }
    @Override public Page<JobRow> jobs(UUID workspaceId, String status, String type, int page, int size) {
        List<JobRow> items = jobRows.stream().filter(j -> status == null || j.status().equals(status))
                .filter(j -> type == null || j.type().equals(type)).toList();
        return new Page<>(items, page, size, items.size());
    }
    @Override public Optional<JobDetail> job(UUID workspaceId, UUID jobId) {
        return jobRows.stream().filter(j -> j.id().equals(jobId)).findFirst().map(j -> new JobDetail(j, null, null, null, List.of()));
    }
    @Override public PublishingFacts publishingFacts(UUID workspaceId, Instant now, Instant since, Instant burstSince, Instant overdueBefore) {
        return publishingFacts;
    }
    @Override public List<ProviderFact> providers(UUID workspaceId, Instant since) { return providers; }
    @Override public Page<PublicationRow> publicationAttention(UUID workspaceId, Instant since, int page, int size) {
        return new Page<>(publications, page, size, publications.size());
    }
    @Override public AutomationFacts automationFacts(UUID workspaceId, Instant now, Instant since) { return automationFacts; }
    @Override public List<RollbackFact> openRollbackRecommendations(UUID workspaceId, int limit) { return rollbacks.stream().limit(limit).toList(); }

    @Override public List<Incident> activeIncidents(UUID workspaceId) {
        return of(workspaceId).values().stream().filter(i -> i.status() == IncidentStatus.ACTIVE)
                .sorted(Comparator.comparing(Incident::firstObservedAt).thenComparing(Incident::id)).toList();
    }
    @Override public Page<Incident> incidents(UUID workspaceId, String status, Severity severity, int page, int size) {
        List<Incident> items = of(workspaceId).values().stream().filter(i -> status == null || i.status().name().equals(status))
                .filter(i -> severity == null || i.severity() == severity).sorted(OpsRules.INCIDENT_ORDER).toList();
        return new Page<>(items, page, size, items.size());
    }
    @Override public Optional<Incident> incident(UUID workspaceId, UUID incidentId) { return Optional.ofNullable(of(workspaceId).get(incidentId)); }
    @Override public boolean insertIncident(UUID workspaceId, DerivedIncident d, Instant now) {
        if (of(workspaceId).values().stream().anyMatch(i -> i.status() == IncidentStatus.ACTIVE && i.key().equals(d.key()))) return false;
        UUID id = UUID.randomUUID();
        of(workspaceId).put(id, new Incident(id, d.key(), d.severity(), IncidentStatus.ACTIVE, d.category(), d.title(), d.conditionCode(),
                d.detail(), d.suggestedAction(), d.subjectType(), d.subjectId(), now, now, null, null, false, true));
        inserts++;
        return true;
    }
    private void replace(UUID incidentId, java.util.function.UnaryOperator<Incident> change) {
        for (Map<UUID, Incident> map : incidents.values()) {
            if (map.containsKey(incidentId)) map.put(incidentId, change.apply(map.get(incidentId)));
        }
    }
    @Override public void updateIncident(UUID incidentId, DerivedIncident d, Instant now) {
        updates++;
        replace(incidentId, i -> new Incident(i.id(), i.key(), d.severity(), i.status(), d.category(), d.title(), d.conditionCode(), d.detail(),
                d.suggestedAction(), d.subjectType(), d.subjectId(), i.firstObservedAt(), now, i.resolvedAt(), i.acknowledgedAt(), i.acknowledged(), true));
    }
    @Override public void touchIncident(UUID incidentId, Instant now) {
        replace(incidentId, i -> new Incident(i.id(), i.key(), i.severity(), i.status(), i.category(), i.title(), i.conditionCode(), i.detail(),
                i.suggestedAction(), i.subjectType(), i.subjectId(), i.firstObservedAt(), now, i.resolvedAt(), i.acknowledgedAt(), i.acknowledged(), true));
    }
    @Override public void resolveIncident(UUID incidentId, Instant now) {
        resolves++;
        replace(incidentId, i -> new Incident(i.id(), i.key(), i.severity(), IncidentStatus.RESOLVED, i.category(), i.title(), i.conditionCode(),
                i.detail(), i.suggestedAction(), i.subjectType(), i.subjectId(), i.firstObservedAt(), now, now, i.acknowledgedAt(), i.acknowledged(), true));
    }
    @Override public boolean acknowledgeIncident(UUID workspaceId, UUID incidentId, UUID userId, Instant now) {
        Incident i = of(workspaceId).get(incidentId);
        if (i == null || i.status() != IncidentStatus.ACTIVE || i.acknowledged()) return false;
        acknowledgedBy.put(incidentId, userId);
        replace(incidentId, x -> new Incident(x.id(), x.key(), x.severity(), x.status(), x.category(), x.title(), x.conditionCode(), x.detail(),
                x.suggestedAction(), x.subjectType(), x.subjectId(), x.firstObservedAt(), x.lastObservedAt(), x.resolvedAt(), now, true, true));
        return true;
    }
    @Override public int purgeResolvedIncidents(Instant before) { return 0; }
}
