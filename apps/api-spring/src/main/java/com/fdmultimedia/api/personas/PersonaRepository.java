package com.fdmultimedia.api.personas;

import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonaRepository extends JpaRepository<Persona, UUID> {

    List<Persona> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace);

    Optional<Persona> findByWorkspaceAndId(Workspace workspace, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Persona p where p.workspace = :workspace and p.id = :id")
    Optional<Persona> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace, @Param("id") UUID id);
}
