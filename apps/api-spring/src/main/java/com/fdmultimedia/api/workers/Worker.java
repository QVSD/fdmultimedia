package com.fdmultimedia.api.workers;

import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workers")
public class Worker {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credential_id", nullable = false)
    private WorkerCredential credential;

    @Column(nullable = false)
    private String name;

    @Column(name = "machine_identifier", nullable = false)
    private String machineIdentifier;

    @Column(name = "operating_system", nullable = false)
    private String operatingSystem;

    @Column(nullable = false)
    private String architecture;

    @Column(name = "cpu_model", nullable = false)
    private String cpuModel;

    @Column(name = "cpu_logical_cores", nullable = false)
    private int cpuLogicalCores;

    @Column(name = "total_memory_bytes", nullable = false)
    private long totalMemoryBytes;

    @Column(name = "gpu_model")
    private String gpuModel;

    @Column(name = "gpu_memory_bytes")
    private Long gpuMemoryBytes;

    @Column(name = "agent_version", nullable = false)
    private String agentVersion;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Worker() {
    }

    public Worker(Workspace workspace, WorkerCredential credential, WorkerRegistrationRequest request, Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.credential = credential;
        this.machineIdentifier = request.machineIdentifier().trim();
        updateFrom(request, now);
        this.registeredAt = now;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (registeredAt == null) {
            registeredAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public void updateFrom(WorkerRegistrationRequest request, Instant now) {
        this.name = request.name().trim();
        this.operatingSystem = request.operatingSystem().trim();
        this.architecture = request.architecture().trim();
        this.cpuModel = request.cpuModel().trim();
        this.cpuLogicalCores = request.cpuLogicalCores();
        this.totalMemoryBytes = request.totalMemoryBytes();
        this.gpuModel = normalizeOptional(request.gpuModel());
        this.gpuMemoryBytes = request.gpuMemoryBytes();
        this.agentVersion = request.agentVersion().trim();
        this.lastSeenAt = now;
        this.updatedAt = now;
    }

    public void heartbeat(Instant now) {
        this.lastSeenAt = now;
        this.updatedAt = now;
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public UUID getId() {
        return id;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public WorkerCredential getCredential() {
        return credential;
    }

    public String getName() {
        return name;
    }

    public String getMachineIdentifier() {
        return machineIdentifier;
    }

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public String getArchitecture() {
        return architecture;
    }

    public String getCpuModel() {
        return cpuModel;
    }

    public int getCpuLogicalCores() {
        return cpuLogicalCores;
    }

    public long getTotalMemoryBytes() {
        return totalMemoryBytes;
    }

    public String getGpuModel() {
        return gpuModel;
    }

    public Long getGpuMemoryBytes() {
        return gpuMemoryBytes;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }
}
