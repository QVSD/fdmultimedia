package com.fdmultimedia.api.workers;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerCredentialRepository extends JpaRepository<WorkerCredential, UUID> {
}
