package com.fdmultimedia.api.publishing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable reconciliation ledger for externally side-effectful provider
 * operations that lack a native idempotency key. As soon as a real provider
 * confirms it created something (e.g. an Instagram media container), that id
 * is persisted here — before the Publication itself is marked PUBLISHED —
 * so a retry after a crash or a lost HTTP response reuses the existing
 * provider operation instead of creating a duplicate external post. Never
 * stores access tokens or raw provider response bodies.
 */
@Entity
@Table(name = "publication_provider_states")
public class PublicationProviderState {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publication_id", nullable = false, unique = true)
    private Publication publication;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_container_id")
    private String providerContainerId;

    @Column(name = "provider_media_id")
    private String providerMediaId;

    @Column(nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PublicationProviderState() {
    }

    public PublicationProviderState(Publication publication, String provider, String providerContainerId, String state, Instant now) {
        this.id = UUID.randomUUID();
        this.publication = publication;
        this.provider = provider;
        this.providerContainerId = providerContainerId;
        this.state = state;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    void prePersist() {
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = timestamp;
        }
        if (updatedAt == null) {
            updatedAt = timestamp;
        }
    }

    public void markPublished(String providerMediaId, Instant now) {
        this.state = "PUBLISHED";
        this.providerMediaId = providerMediaId;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public Publication getPublication() { return publication; }
    public String getProvider() { return provider; }
    public String getProviderContainerId() { return providerContainerId; }
    public String getProviderMediaId() { return providerMediaId; }
    public String getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
