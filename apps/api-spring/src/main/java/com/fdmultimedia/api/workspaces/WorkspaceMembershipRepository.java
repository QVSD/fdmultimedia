package com.fdmultimedia.api.workspaces;

import com.fdmultimedia.api.users.AppUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMembershipRepository extends JpaRepository<WorkspaceMembership, UUID> {

    @EntityGraph(attributePaths = {"workspace", "user"})
    List<WorkspaceMembership> findByUserOrderByCreatedAtAsc(AppUser user);

    @EntityGraph(attributePaths = {"workspace", "user"})
    Optional<WorkspaceMembership> findByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    boolean existsByUserAndWorkspace(AppUser user, Workspace workspace);
}
