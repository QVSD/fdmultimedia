package com.fdmultimedia.api.workers;

import com.fdmultimedia.api.workspaces.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerRepository extends JpaRepository<Worker, UUID> {

    List<Worker> findByWorkspaceOrderByNameAsc(Workspace workspace);

    Optional<Worker> findByWorkspaceAndId(Workspace workspace, UUID id);

    Optional<Worker> findByWorkspaceAndMachineIdentifier(Workspace workspace, String machineIdentifier);
}
